package com.code402;

import java.io.*;
import java.net.*;
import org.apache.commons.compress.compressors.gzip.*;
import org.netpreserve.jwarc.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.type.*;
import com.fasterxml.jackson.databind.ObjectMapper;

class Single {
  static boolean USE_SSL = false;
  static int NUM_RECORDS = 1000 * 1000;
  static int NUM_CORES = 1;
  static int NUM_DOWNLOAD_THREADS = 100;
  static int BATCH_SIZE = 1000;
  static LongAdder records = new LongAdder();
  static InputStream getStream(String url) throws Exception {
    return new GzipCompressorInputStream(new URL(url).openStream(), true);
  }
  static JsonFactory jsonFactory = new JsonFactory();
  static ObjectMapper mapper = new ObjectMapper();
  static TypeReference<HashMap<String, Object>> mapStringObject =
    new TypeReference<HashMap<String, Object>>() {};

  static {
    USE_SSL = System.getenv("USE_SSL") != null;

    if(System.getenv("NUM_RECORDS") != null)
      NUM_RECORDS = Integer.parseInt(System.getenv("NUM_RECORDS"));

    if(System.getenv("NUM_CORES") != null)
      NUM_CORES = Integer.parseInt(System.getenv("NUM_CORES"));

    if(System.getenv("BATCH_SIZE") != null)
      BATCH_SIZE = Integer.parseInt(System.getenv("BATCH_SIZE"));

    if(System.getenv("NUM_DOWNLOAD_THREADS") != null)
      NUM_DOWNLOAD_THREADS = Integer.parseInt(System.getenv("NUM_DOWNLOAD_THREADS"));
  }

  static long startTime = System.currentTimeMillis();

  static ThreadPoolExecutor downloadPool =
      new ThreadPoolExecutor(NUM_DOWNLOAD_THREADS, NUM_DOWNLOAD_THREADS, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(125000));

  static String[] getCdxUrls(String warcsUrl, int howMany) throws Exception {
    InputStream is = getStream(warcsUrl);
    try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
      // The CDXes are ordered by domain name, which biases the entries.
      // Mitigate that a little bit by randomizing them.
      String[] entries = br.lines().filter(line -> line.endsWith(".gz")).toArray(String[]::new);
      Arrays.sort(entries, new Comparator<String>() {
        public int compare(String a, String b) {
          return Integer.compare(a.hashCode(), b.hashCode());
        }
      });
      String[] rv = new String[howMany];
      System.arraycopy(entries, 0, rv, 0, howMany);
      return rv;
    }
  }

  static class ProcessCdxEntry implements Callable<byte[]> {
    String url;
    int offset, length;

    ProcessCdxEntry(String url, int offset, int length) {
      this.url = url;
      this.offset = offset;
      this.length = length;
    }

    public byte[] call() {
      int attempts = 5;
      while(attempts > 0) {
        attempts--;

        try {
          byte[] rv = new byte[length];
          int start = 0;
          HttpURLConnection conn = (HttpURLConnection)(new URL(url).openConnection());
          conn.setConnectTimeout(5000);
          conn.setReadTimeout(5000);
          String range = "bytes=" + offset + "-" + (offset + length - 1);
          conn.setRequestProperty("Range", range);

          InputStream is = conn.getInputStream();
          boolean go = true;
          while(go) {
            int read = is.read(rv, start, rv.length - start);
            if(read == -1) {
              go = false;
            } else if(read == 0) {
              throw new Exception("this should never happen");
            } else {
              start += read;
            }

            if(start == length)
              go = false;
          }

          return rv;
        } catch (Exception e) {
          System.out.println(e.getMessage());
        }
      }

      System.out.println("failed to fetch entry");
      System.exit(1);
      return null;
    }
  }

  static class ProcessCdx implements Runnable {
    String url;
    CountDownLatch latch;
    ProcessCdx(String url, CountDownLatch latch) {
      this.url = url;
      this.latch = latch;
    }

    public void run() {
      try {
        System.out.println(url);

        // Read the CDX entries.
        InputStream cdxIs = getStream("http" + (USE_SSL ? "s" : "") + "://commoncrawl.s3.amazonaws.com/" + url);
        BufferedReader cdxLines = new BufferedReader(new InputStreamReader(cdxIs));

        Vector<Future<byte[]>> bytesFutures = new Vector<Future<byte[]>>();
        byte[] buffer = new byte[2048];
        while(true) {
          bytesFutures.clear();

          for(int i = 0; i < BATCH_SIZE; i++) {
            String cdxLine = cdxLines.readLine();
            if(cdxLine == null)
              break;

            String json = cdxLine.substring(cdxLine.indexOf(" {") + 1);
            HashMap<String, Object> entry = mapper.readValue(json, mapStringObject);
            //System.out.println(entry);
            bytesFutures.add(downloadPool.submit(
                new ProcessCdxEntry(
                  "http" + (USE_SSL ? "s" : "") + "://commoncrawl.s3.amazonaws.com/" + entry.get("filename").toString(),
                  Integer.parseInt(entry.get("offset").toString()),
                  Integer.parseInt(entry.get("length").toString())
                )
            ));
          }

          for(Future<byte[]> bytes : bytesFutures) {
            ByteArrayInputStream bais = new ByteArrayInputStream(bytes.get());
            WarcReader reader = new WarcReader(bais);
            WarcRecord record = reader.next().get();
            if(record instanceof WarcResponse) {
              records.increment();
              // consume the body
              WarcResponse response = (WarcResponse)record;
              InputStream is = response.body().stream();
              while(true) {
                int read = is.read(buffer);
                if(read == -1)
                  break;
              }
              is.close();
            }

            if(records.sum() > NUM_RECORDS)
              return;
          }
        }
      } catch(Exception e) {
        e.printStackTrace();
        System.exit(1);
      } finally {
        latch.countDown();
      }
    }
  }

  public static void main(String[] args) throws Exception {
    CountDownLatch latch = new CountDownLatch(NUM_CORES);

    Thread printer = new Thread() {
      public void run() {
        while(true) {
          try {
            Thread.sleep(1000);
          } catch (InterruptedException ie) {
          }
          printRate();
        }
      }
    };

    printer.start();

    String[] urls = getCdxUrls(
      "http" + (USE_SSL ? "s" : "") + "://commoncrawl.s3.amazonaws.com/crawl-data/CC-MAIN-2019-30/cc-index.paths.gz",
      NUM_CORES
    );

    ThreadPoolExecutor pool =
      new ThreadPoolExecutor(NUM_CORES, NUM_CORES, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(100));
    for(String url: urls) {
      ProcessCdx pc = new ProcessCdx(url, latch);
      pool.submit(pc);
    }

    latch.await();

    printRate();
    System.exit(0);
  }

  static void printRate() {
    long elapsed = System.currentTimeMillis() - startTime;
    System.out.println("Read " + records.sum() + " records in " + elapsed + "ms, " + (records.doubleValue() / (elapsed / 1000.0)) + " records/sec.");
  }
}
