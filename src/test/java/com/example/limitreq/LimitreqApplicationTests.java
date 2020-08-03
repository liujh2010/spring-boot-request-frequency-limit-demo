package com.example.limitreq;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.lang.Nullable;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@SpringBootTest
class LimitreqApplicationTests {

    private class ConcurrentTestResult {
        int testCase;
        int statusCode;

        public ConcurrentTestResult(int testCase, int statusCode) {
            this.testCase = testCase;
            this.statusCode = statusCode;
        }
    }

    @Test
    void multiConcurrentTest() throws ExecutionException, InterruptedException {
        int times = 3;
        for (int i = 0; i < times; i++) {
            long start = System.currentTimeMillis();
            concurrentTest();
            System.out.println("No." + (i + 1) + " done in " + (System.currentTimeMillis() - start) + " ms");
        }
    }

    @Test
    void concurrentTest() throws InterruptedException, ExecutionException {
        int testTime = 10000;
        int caseNum = 3;
        int tolerance = 10;

        int helloApiLimit = 10000;
        int fuckApiLimit = 5000;

        int helloApiSucceedNum = 0;
        int helloApiRefuseNum = 0;
        int helloApiFailedNum = 0;

        int fuckApiSucceedNum = 0;
        int fuckApiRefuseNum = 0;
        int fuckApiFailedNum = 0;

        int yeahApiSucceedNum = 0;
        int yeahApiRefuseNum = 0;
        int yeahApiFailedNum = 0;

        ExecutorService threadPool = Executors.newFixedThreadPool(10);
        List<Future<ConcurrentTestResult>> futures = new ArrayList<>(testTime * caseNum);
        CountDownLatch latch = new CountDownLatch(testTime * caseNum);

        for (int i = 0; i < testTime; i++) {
            futures.add(threadPool.submit(() -> {
                ConcurrentTestResult res = request("http://127.0.0.1:8080/tests/test/hello", "GET", 1);
                latch.countDown();
                return res;
            }));
            futures.add(threadPool.submit(() -> {
                ConcurrentTestResult res = request("http://127.0.0.1:8080/tests/test/fuck", "POST", 2);
                latch.countDown();
                return res;
            }));
            futures.add(threadPool.submit(() -> {
                ConcurrentTestResult res = request("http://127.0.0.1:8080/tests/test/yeah", "GET", 3);
                latch.countDown();
                return res;
            }));
        }

        long startTime = System.currentTimeMillis();
        latch.await();
        long spendTime = System.currentTimeMillis() - startTime;
        long expectHelloApiSucceedNum = spendTime / helloApiLimit;
        long expectFuckApiSucceedNum = spendTime / fuckApiLimit;

        for (Future<ConcurrentTestResult> item : futures) {
            ConcurrentTestResult result = item.get();
            if (result.testCase == 1) {
                switch (result.statusCode) {
                    case 200:
                        helloApiSucceedNum++;
                        break;
                    case 429:
                        helloApiRefuseNum++;
                        break;
                    default:
                        helloApiFailedNum++;
                }
            } else if (result.testCase == 2) {
                switch (result.statusCode) {
                    case 200:
                        fuckApiSucceedNum++;
                        break;
                    case 429:
                        fuckApiRefuseNum++;
                        break;
                    default:
                        fuckApiFailedNum++;
                }
            } else {
                switch (result.statusCode) {
                    case 200:
                        yeahApiSucceedNum++;
                        break;
                    case 429:
                        yeahApiRefuseNum++;
                        break;
                    default:
                        yeahApiFailedNum++;
                }
            }
        }
        boolean helloApiPass = false;
        boolean fuckApiPass = false;
        if (helloApiSucceedNum <= (expectHelloApiSucceedNum + tolerance) && helloApiSucceedNum >= (expectHelloApiSucceedNum - tolerance)) {
            helloApiPass = true;
        }
        if (fuckApiSucceedNum <= (expectFuckApiSucceedNum + tolerance) && fuckApiSucceedNum >= (expectFuckApiSucceedNum - tolerance)) {
            fuckApiPass = true;
        }

        System.out.println("Hello API " + "expect succeed number: " + expectHelloApiSucceedNum + ", Real succeed number: " + helloApiSucceedNum + ", Real refuse number: " + helloApiRefuseNum + ", Real failed number: " + helloApiFailedNum);
        System.out.println("Fuck API " + "expect succeed number: " + expectFuckApiSucceedNum + ", Real succeed number: " + fuckApiSucceedNum + ", Real refuse number: " + fuckApiRefuseNum + ", Real failed number: " + fuckApiFailedNum);
        System.out.println("Yeah API " + "Real succeed number: " + yeahApiSucceedNum + ", Real refuse number: " + yeahApiRefuseNum + ", Real failed number: " + yeahApiFailedNum);

        Assertions.assertTrue(helloApiPass);
        Assertions.assertTrue(fuckApiPass);
    }

    private ConcurrentTestResult request(String url, String method, int testCase) {
        int statusCode = method.equals("GET") ? doGet(url) : doPost(url, null);
        return new ConcurrentTestResult(testCase, statusCode);
    }

    public static int doGet(String httpUrl) {
        //链接
        HttpURLConnection connection = null;
        InputStream is = null;
        BufferedReader br = null;
        try {
            //创建连接
            URL url = new URL(httpUrl);
            connection = (HttpURLConnection) url.openConnection();
            //设置请求方式
            connection.setRequestMethod("GET");
            //设置连接超时时间
            connection.setReadTimeout(15000);
            //开始连接
            connection.connect();
            //获取响应数据
            return connection.getResponseCode();
        } catch (IOException e) {
//            e.printStackTrace();
        } finally {
            if (null != br) {
                try {
                    br.close();
                } catch (IOException e) {
//                    e.printStackTrace();
                }
            }
            if (null != is) {
                try {
                    is.close();
                } catch (IOException e) {
//                    e.printStackTrace();
                }
            }
            //关闭远程连接
            connection.disconnect();
        }
        return -1;
    }

    /**
     * Http post请求
     *
     * @param httpUrl 连接
     * @param param   参数
     * @return
     */
    public static int doPost(String httpUrl, @Nullable String param) {
        StringBuffer result = new StringBuffer();
        //连接
        HttpURLConnection connection = null;
        OutputStream os = null;
        InputStream is = null;
        BufferedReader br = null;
        try {
            //创建连接对象
            URL url = new URL(httpUrl);
            //创建连接
            connection = (HttpURLConnection) url.openConnection();
            //设置请求方法
            connection.setRequestMethod("POST");
            //设置连接超时时间
            connection.setConnectTimeout(15000);
            //设置读取超时时间
            connection.setReadTimeout(15000);
            //DoOutput设置是否向httpUrlConnection输出，DoInput设置是否从httpUrlConnection读入，此外发送post请求必须设置这两个
            //设置是否可读取
            connection.setDoOutput(true);
            connection.setDoInput(true);
            //设置通用的请求属性
            connection.setRequestProperty("accept", "*/*");
            connection.setRequestProperty("connection", "Keep-Alive");
            connection.setRequestProperty("user-agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1; SV1)");
            connection.setRequestProperty("Content-Type", "application/json;charset=utf-8");

            //拼装参数
            if (null != param && param.equals("")) {
                //设置参数
                os = connection.getOutputStream();
                //拼装参数
                os.write(param.getBytes("UTF-8"));
            }
            //设置权限
            //设置请求头等
            //开启连接
            connection.connect();
            //读取响应
            return connection.getResponseCode();

        } catch (MalformedURLException e) {
//            e.printStackTrace();
        } catch (IOException e) {
//            e.printStackTrace();
        } finally {
            //关闭连接
            if (br != null) {
                try {
                    br.close();
                } catch (IOException e) {
//                    e.printStackTrace();
                }
            }
            if (os != null) {
                try {
                    os.close();
                } catch (IOException e) {
//                    e.printStackTrace();
                }
            }
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
//                    e.printStackTrace();
                }
            }
            //关闭连接
            connection.disconnect();
        }
        return -1;
    }
}


