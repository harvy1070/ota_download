package com.example.download_test;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.logging.HttpLoggingInterceptor;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private final String TAG = MainActivity.class.getSimpleName();
    private final String DOWNLOAD_URL = "https://s3.ap-southeast-2.amazonaws.com/avn.directed.kr/firmware/TEST/random_file_100MB.bin";
    private final String PREFS_NAME = "DownloadPrefs";
    private final String KEY_DOWNLOADED_BYTES = "downloadedBytes";
    private final String KEY_TOTAL_BYTES = "totalBytes";
    private final String KEY_DOWNLOAD_ID = "downloadId";
    private Map<String, X509Certificate> loadedCertificates = new HashMap<>();
    private TextView tvCurrentVersion;
    private TextView tvStatus;
    private Button btnDownload;
    private ProgressBar progressBar;

    private double currentVersion = 1.0;
    private File downloadFile;
    private File tempFile; // 임시 다운로드 파일
    private long downloadStartTime; // 다운로드 시작 시간 저장 변수
    private boolean isDownloading = false;
    private String currentDownloadId; // 현재 다운로드의 고유 ID

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // UI 요소 초기화
        tvCurrentVersion = findViewById(R.id.tvCurrentVersion);
        tvStatus = findViewById(R.id.tvStatus);
        btnDownload = findViewById(R.id.btnDownload);
        progressBar = findViewById(R.id.progressBar);

        // 다운로드 파일 경로 설정
        downloadFile = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.bin");
        tempFile = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.bin.tmp");

        // 버전 표시
        tvCurrentVersion.setText("현재 버전: " + String.format("%.1f", currentVersion));

        // 다운로드 버튼 이벤트 설정
        btnDownload.setOnClickListener(this);

        // 이전 다운로드 상태 확인
        checkPreviousDownload();
    }

    private void checkPreviousDownload() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long downloadedBytes = prefs.getLong(KEY_DOWNLOADED_BYTES, 0);
        long totalBytes = prefs.getLong(KEY_TOTAL_BYTES, 0);
        String downloadId = prefs.getString(KEY_DOWNLOAD_ID, "");

        if (downloadedBytes > 0 && totalBytes > 0 && tempFile.exists() && tempFile.length() == downloadedBytes) {
            int progress = (int) (downloadedBytes * 100 / totalBytes);
            runOnUiThread(() -> {
                tvStatus.setText(String.format("이전 다운로드 발견: %d%% (%s / %s)",
                        progress, formatFileSize(downloadedBytes), formatFileSize(totalBytes)));
                Toast.makeText(this, "이전에 다운로드한 파일을 이어받을 수 있습니다.", Toast.LENGTH_LONG).show();
            });
            currentDownloadId = downloadId;
        }
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.btnDownload) {
            if (isDownloading) {
                // 이미 다운로드 중이면 취소
                Toast.makeText(this, "다운로드 취소 중...", Toast.LENGTH_SHORT).show();
                isDownloading = false;
                btnDownload.setText("다운로드");
                return;
            }

            // UI 업데이트 - 메인 스레드에서 실행
            progressBar.setVisibility(View.VISIBLE);
            tvStatus.setText("다운로드 준비 중...");
            btnDownload.setText("취소");
            isDownloading = true;

            // 새 스레드에서 다운로드 시작
            new Thread(() -> {
                downloadWithResume();
            }).start();
        }
    }

    // 이어받기 기능이 있는 다운로드 메서드
    private void downloadWithResume() {
        // 다운로드 시작 시간 기록
        downloadStartTime = System.currentTimeMillis();

        // 새 다운로드 ID 생성 (이전 다운로드가 없을 경우)
        if (currentDownloadId == null || currentDownloadId.isEmpty()) {
            currentDownloadId = String.valueOf(System.currentTimeMillis());
        }

        // HTTP 로깅 인터셉터 설정
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor(message ->
                Log.v(TAG, "OkHttp: " + message));
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);

        // 커스텀 인증서를 사용하는 OkHttp 클라이언트 생성
        OkHttpClient client = getSecureOkHttpClient();
        // 로깅 인터셉터 추가
        client = client.newBuilder().addInterceptor(logging).build();

        // 이미 다운로드된 바이트 수 확인
        long downloadedBytes = 0;
        if (tempFile.exists()) {
            downloadedBytes = tempFile.length();
            Log.d(TAG, "이전에 다운로드된 파일 발견: " + formatFileSize(downloadedBytes));
        }

        final long finalDownloadedBytes = downloadedBytes;

        // 요청 빌더
        Request.Builder requestBuilder = new Request.Builder()
                .url(DOWNLOAD_URL);

        // Range 헤더 추가 (이어받기)
        if (downloadedBytes > 0) {
            requestBuilder.addHeader("Range", "bytes=" + downloadedBytes + "-");
            Log.d(TAG, "이어받기 요청: " + downloadedBytes + " 바이트부터");
            runOnUiThread(() -> {
                tvStatus.setText("이어받기 준비 중... (" + formatFileSize(finalDownloadedBytes) + "부터)");
            });
        } else {
            runOnUiThread(() -> {
                tvStatus.setText("다운로드 준비 중...");
            });
        }

        Request request = requestBuilder.build();
        Log.d(TAG, "HTTPS 요청 시작: " + DOWNLOAD_URL);

        try {
            // 사용자가 취소했는지 확인
            if (!isDownloading) {
                return;
            }

            // 동기 방식으로 요청 실행 (이미 백그라운드 스레드에 있으므로 가능)
            Response response = client.newCall(request).execute();

            if (!response.isSuccessful()) {
                runOnUiThread(() -> {
                    tvStatus.setText("서버 오류: " + response.code());
                    progressBar.setVisibility(View.INVISIBLE);
                    btnDownload.setText("다운로드");
                    isDownloading = false;
                });
                return;
            }

            // HTTPS 연결 정보 로깅
            String protocol = response.protocol().toString();
            String cipher = response.handshake() != null ?
                    response.handshake().cipherSuite().toString() : "알 수 없음";

            Log.d(TAG, "HTTPS 연결 성공");
            Log.d(TAG, "프로토콜: " + protocol);
            Log.d(TAG, "암호화 스위트: " + cipher);

            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                runOnUiThread(() -> {
                    tvStatus.setText("응답 데이터가 없습니다");
                    progressBar.setVisibility(View.INVISIBLE);
                    btnDownload.setText("다운로드");
                    isDownloading = false;
                });
                return;
            }

            // 전체 파일 크기 확인
            long totalBytes;
            if (response.code() == 206) { // 부분 콘텐츠
                String contentRange = response.header("Content-Range");
                if (contentRange != null && contentRange.startsWith("bytes ")) {
                    String[] parts = contentRange.substring(6).split("/");
                    if (parts.length == 2) {
                        totalBytes = Long.parseLong(parts[1]);
                    } else {
                        totalBytes = downloadedBytes + responseBody.contentLength();
                    }
                } else {
                    totalBytes = downloadedBytes + responseBody.contentLength();
                }
            } else {
                totalBytes = responseBody.contentLength();
                // 새로운 다운로드인 경우 이전 임시 파일 삭제
                if (tempFile.exists()) {
                    tempFile.delete();
                    downloadedBytes = 0;
                }
            }

            // 다운로드 정보 저장
            saveDownloadInfo(downloadedBytes, totalBytes, currentDownloadId);

            BufferedSource source = responseBody.source();
            final long finalTotalBytes = totalBytes;

            runOnUiThread(() -> {
                if (finalDownloadedBytes > 0) {
                    tvStatus.setText("이어받기 시작 (" + formatFileSize(finalDownloadedBytes) + " / " +
                            formatFileSize(finalTotalBytes) + ")");
                } else {
                    tvStatus.setText("다운로드 시작 (총 " + formatFileSize(finalTotalBytes) + ")");
                }

                // 이미 다운로드된 부분에 대한 진행률 설정
                if (finalTotalBytes > 0) {
                    int initialProgress = (int) (finalDownloadedBytes * 100 / finalTotalBytes);
                    progressBar.setProgress(initialProgress);
                }
            });

            // 다운로드 시작 로그
            Log.d(TAG, "다운로드 시작. 총 파일 크기: " + formatFileSize(totalBytes) +
                    ", 기존 다운로드: " + formatFileSize(downloadedBytes));

            // 파일 저장 준비
            BufferedSink sink = null;
            try {
                // 이어쓰기 모드로 파일 열기
                sink = Okio.buffer(Okio.appendingSink(tempFile));

                // 버퍼 설정
                Buffer buffer = new Buffer();
                long bytesReadThisSession = 0;
                long bytesReported = downloadedBytes;
                int bufferSize = 8 * 1024; // 8KB

                // 스트리밍 방식으로 다운로드
                while (isDownloading) {
                    long read = source.read(buffer, bufferSize);
                    if (read == -1) break;

                    sink.write(buffer, read);
                    bytesReadThisSession += read;
                    long totalBytesDownloaded = downloadedBytes + bytesReadThisSession;

                    // 진행 상태 업데이트 (약 5% 단위로)
                    if (totalBytes > 0) {
                        final int progress = (int) (totalBytesDownloaded * 100 / totalBytes);
                        long reportThreshold = totalBytes / 20; // 5% 단위
                        final int roundedProgress = (progress / 5) * 5; // 5의 배수로 반올림
                        final long finalTotalBytesDownloaded = totalBytesDownloaded;

                        if (totalBytesDownloaded - bytesReported >= reportThreshold) {
                            bytesReported = totalBytesDownloaded;

                            // 로그 기록
                            Log.v(TAG, String.format("다운로드 진행: %d%% (%s / %s)",
                                    progress, formatFileSize(totalBytesDownloaded), formatFileSize(totalBytes)));

                            // 다운로드 정보 저장 (5% 단위로)
                            saveDownloadInfo(totalBytesDownloaded, totalBytes, currentDownloadId);

                            // UI 업데이트
                            runOnUiThread(() -> {
                                progressBar.setProgress(progress); // 실제 진행도는 정확하게 표시
                                tvStatus.setText(String.format("다운로드 중 %d%% (%s / %s)",
                                        roundedProgress, formatFileSize(finalTotalBytesDownloaded), formatFileSize(finalTotalBytes)));
                            });
                        }
                    }
                }

                // 다운로드 취소 확인
                if (!isDownloading) {
                    Log.d(TAG, "다운로드 취소됨");
                    runOnUiThread(() -> {
                        tvStatus.setText("다운로드 취소됨");
                        btnDownload.setText("다운로드");
                        isDownloading = false;
                    });
                    return;
                }

                // 다운로드 완료
                sink.flush();

                // 임시 파일을 실제 파일로 이동
                if (downloadFile.exists()) {
                    downloadFile.delete();
                }
                if (!tempFile.renameTo(downloadFile)) {
                    throw new IOException("파일 이름 변경 실패");
                }

                // 다운로드 정보 초기화
                clearDownloadInfo();

                // 다운로드 소요 시간 계산
                long downloadEndTime = System.currentTimeMillis();
                long downloadDuration = downloadEndTime - downloadStartTime;
                String formattedTime = formatDownloadTime(downloadDuration);

                // 로그 기록
                Log.d(TAG, "다운로드 완료. 파일 저장 위치: " + downloadFile.getAbsolutePath());
                Log.d(TAG, "파일 크기: " + formatFileSize(downloadFile.length()));
                Log.d(TAG, "다운로드 소요 시간: " + formattedTime);

                // UI 업데이트
                runOnUiThread(() -> {
                    // 버전 업데이트 (테스트용)
                    currentVersion += 0.1;
                    tvCurrentVersion.setText("현재 버전: " + String.format("%.1f", currentVersion));

                    // 상태 업데이트
                    tvStatus.setText("다운로드 완료: " + formatFileSize(downloadFile.length()) +
                            " (소요 시간: " + formattedTime + ")");
                    progressBar.setProgress(100);
                    btnDownload.setText("다운로드");
                    isDownloading = false;

                    Toast.makeText(MainActivity.this, "다운로드 완료! 소요 시간: " + formattedTime, Toast.LENGTH_SHORT).show();
                });

            } finally {
                if (sink != null) {
                    try {
                        sink.close();
                    } catch (IOException e) {
                        Log.e(TAG, "리소스 정리 오류", e);
                    }
                }
                responseBody.close();
            }

        } catch (IOException e) {
            Log.e(TAG, "다운로드 중 오류 발생", e);

            // 다운로드 상태 저장 (다시 시도할 수 있도록)
            if (isDownloading && tempFile.exists()) {
                long currentSize = tempFile.length();
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                long totalBytes = prefs.getLong(KEY_TOTAL_BYTES, 0);

                if (totalBytes > 0) {
                    saveDownloadInfo(currentSize, totalBytes, currentDownloadId);

                    runOnUiThread(() -> {
                        int progress = (int) (currentSize * 100 / totalBytes);
                        tvStatus.setText(String.format("다운로드 일시 중단: %d%% (%s / %s)",
                                progress, formatFileSize(currentSize), formatFileSize(totalBytes)));
                        Toast.makeText(MainActivity.this,
                                "연결이 끊겼습니다. 나중에 이어받기가 가능합니다.",
                                Toast.LENGTH_LONG).show();
                        btnDownload.setText("다운로드");
                        isDownloading = false;
                    });
                    return;
                }
            }

            runOnUiThread(() -> {
                tvStatus.setText("다운로드 실패: " + e.getMessage());
                progressBar.setVisibility(View.INVISIBLE);
                btnDownload.setText("다운로드");
                isDownloading = false;
                Toast.makeText(MainActivity.this, "다운로드 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            });
        }
    }

    // 다운로드 정보 저장
    private void saveDownloadInfo(long downloadedBytes, long totalBytes, String downloadId) {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putLong(KEY_DOWNLOADED_BYTES, downloadedBytes);
        editor.putLong(KEY_TOTAL_BYTES, totalBytes);
        editor.putString(KEY_DOWNLOAD_ID, downloadId);
        editor.apply();
    }

    // 다운로드 정보 초기화
    private void clearDownloadInfo() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.remove(KEY_DOWNLOADED_BYTES);
        editor.remove(KEY_TOTAL_BYTES);
        editor.remove(KEY_DOWNLOAD_ID);
        editor.apply();
    }

    // 파일 크기를 읽기 쉬운 형태로 변환 (B, KB, MB, GB)
    private String formatFileSize(long size) {
        if (size <= 0) return "0 B";

        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));

        return String.format("%.2f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    // 다운로드 시간을 읽기 쉬운 형태로 변환 (밀리초 -> 시:분:초.밀리초)
    private String formatDownloadTime(long millis) {
        if (millis < 1000) {
            return millis + "ms";
        }

        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            return String.format("%d시간 %d분 %d초", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%d분 %d초", minutes, seconds % 60);
        } else {
            return String.format("%.1f초", millis / 1000.0);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 앱이 종료될 때 다운로드 중이었다면 상태 저장
        if (isDownloading && tempFile.exists()) {
            long currentSize = tempFile.length();
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long totalBytes = prefs.getLong(KEY_TOTAL_BYTES, 0);

            if (totalBytes > 0) {
                saveDownloadInfo(currentSize, totalBytes, currentDownloadId);
                Log.d(TAG, "앱 종료 시 다운로드 상태 저장: " + currentSize + "/" + totalBytes);
            }
        }
    }

    private OkHttpClient getSecureOkHttpClient() {
        try {
            Log.d(TAG, "========== 인증서 로드 시작 ==========");

            // 키 스토어 초기화
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            Log.d(TAG, "빈 키스토어 생성 완료");

            // 인증서 팩토리 생성
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Log.d(TAG, "X.509 인증서 팩토리 생성 완료");

            // 인증서 디렉토리 접근
            File certsDir = new File("/sdcard/certificates/cacerts/");
            Log.d(TAG, "인증서 디렉토리 경로 >> " + certsDir.getAbsolutePath());

            int loadedCerts = 0;

            // 디렉토리 내 모든 파일을 인증서로 취급(강제로)
            if (certsDir.exists() && certsDir.isDirectory()) {
                File[] certFiles = certsDir.listFiles();
                if (certFiles != null & certFiles.length > 0) {
                    Log.d(TAG, "인증서 디렉토리에서 " + certFiles.length + "개의 파일 발견");

                    for (File certFile : certFiles) {
                        if (certFile.isFile()) {
                            try {
                                Log.d(TAG, "인증서 파일 시도 >> " + certFile.getName());

                                InputStream caInput = new FileInputStream(certFile);
                                try {
                                    Certificate ca = cf.generateCertificate(caInput);
                                    X509Certificate x509Cert = (X509Certificate) ca;
                                    loadedCertificates.put(certFile.getName(), x509Cert);
                                    String subjectDN = x509Cert.getSubjectDN().getName();
                                    String issuerDN = x509Cert.getIssuerDN().getName();
                                    String serialNumber = x509Cert.getSerialNumber().toString(16);

                                    Log.d(TAG, "인증서 로드 성공 >> " + certFile.getName());
                                    Log.d(TAG, "주체 >> " + subjectDN);
                                    Log.d(TAG, "발급자 >> " + issuerDN);
                                    Log.d(TAG, "일련번호 >> " + serialNumber);
                                    Log.d(TAG, "유효기간 >> " + x509Cert.getNotBefore() + " ~ " + x509Cert.getNotAfter());

                                    // 키스토어에 인증서 추가(파일명 별칭으로 사용)
                                    keyStore.setCertificateEntry(certFile.getName(), ca);
                                    loadedCerts++;

                                    Log.d(TAG, "인증서 키스토어 추가 완료 >> " + certFile.getName());
                                } finally {
                                    caInput.close();
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "인증서 로드 실패 >> " + certFile.getName(), e);
                                // 개별 인증서 로드 실패는 무시하면서 계속 진행하도록 함
                            }
                        } else {
                            Log.d(TAG, "건너 뜀 (디렉토리) >> " + certFile.getName());
                        }
                    }
                } else {
                    Log.e(TAG, "인증서 파일이 없음 >> " + certsDir.getAbsolutePath());
                    throw new FileNotFoundException("인증서 파일 없음");
                }
            } else {
                Log.e(TAG, "인증서 디렉토리가 없음 >> " + certsDir.getAbsolutePath());
                throw new FileNotFoundException("인증서 디렉토리가 없음");
            }

            Log.d(TAG, "총 " + loadedCerts + "개의 인증서 로드 완료");

            if (loadedCerts == 0) {
                Log.e(TAG, "유효한 인증서가 하나도 로드되지 않음");
                throw new Exception("유효한 인증서가 하나도 로드되지 않음");
            }

            // TrustManagerFactory 생성
            Log.d(TAG, "=========== Trust Manager Factory 생성 시작 ===========");
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);
            Log.d(TAG, "TrustManagerFactory 초기화 완료 >> " + TrustManagerFactory.getDefaultAlgorithm());

            // SSLContext 설정
            Log.d(TAG, "=========== SSL Context 설정 시작 ===========");
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);
            Log.d(TAG, "SSL Context 초기화 완료 (프로토콜 >> TLS)");

            // TrustManager 정보 로깅
            TrustManager[] trustManagers = tmf.getTrustManagers();
            Log.d(TAG, "TrustManager 수 >> " + trustManagers.length);

            for (int i = 0; i < trustManagers.length; i++) {
                TrustManager tm = trustManagers[i];
                Log.d(TAG, "TrustManager[" + i + "] 유형 >> " + tm.getClass().getName());

                if (tm instanceof X509TrustManager) {
                    X509TrustManager x509tm = (X509TrustManager) tm;
                    X509Certificate[] acceptedIssuers = x509tm.getAcceptedIssuers();
                    Log.d(TAG, "수락된 발급자 수 >> " + acceptedIssuers.length);

                    for (int j = 0; j < Math.min(acceptedIssuers.length, 10); j++) {
                        Log.d(TAG, "    발급자[" + j + "] >> " + acceptedIssuers[j].getSubjectDN().getName());
                    }

                    if (acceptedIssuers.length > 10) {
                        Log.d(TAG, "    ... 그 외 " + (acceptedIssuers.length - 10) + "개");
                    }
                }
            }

            // OkHttp 클라이언트에 SSL 설정 적용
            Log.d(TAG, "커스텀 인증서를 사용하는 OkHttpClient 생성");
            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(),
                            (X509TrustManager) tmf.getTrustManagers()[0])
                    .hostnameVerifier(new HostnameVerifier() {
                        @Override
                        public boolean verify(String hostname, SSLSession session) {
                            Log.d(TAG, "호스트명 검증 >> " + hostname);
                            try {
                                Certificate[] certs = session.getPeerCertificates();
                                Log.d(TAG, "서버 인증서 체인 >> " + certs.length + "개");

                                // 서버 인증서 정보 먼저 출력
                                for (int i = 0; i < certs.length; i++) {
                                    if (certs[i] instanceof X509Certificate) {
                                        X509Certificate cert = (X509Certificate) certs[i];
                                        Log.d(TAG, "서버 인증서[" + i + "] >> " + cert.getSubjectDN().getName());
                                        Log.d(TAG, "발급자 >> " + cert.getIssuerDN().getName());
                                    }
                                }

                                // 어떤 인증서 파일이 사용되었는지 검사
                                Log.d(TAG, "========= 로드된 인증서 중 사용된 인증서 확인 =========");
                                boolean foundMatch = false;

                                for (Map.Entry<String, X509Certificate> entry : loadedCertificates.entrySet()) {
                                    String fileName = entry.getKey();
                                    X509Certificate loadedCert = entry.getValue();

                                    // 서버 인증서 체인과 로드된 인증서 비교
                                    for (Certificate serverCert : certs) {
                                        if (serverCert instanceof X509Certificate) {
                                            X509Certificate serverX509 = (X509Certificate) serverCert;

                                            // 인증서의 발급자가 로드된 인증서의 주체와 일치하는지 확인 (인증 체인 검증)
                                            if (serverX509.getIssuerDN().getName().equals(loadedCert.getSubjectDN().getName())) {
                                                Log.d(TAG, "MATCH FOUND: 파일 " + fileName + "이(가) 인증 체인에 사용됨");
                                                Log.d(TAG, "  서버 인증서 발급자: " + serverX509.getIssuerDN().getName());
                                                Log.d(TAG, "  로드된 인증서 주체: " + loadedCert.getSubjectDN().getName());
                                                foundMatch = true;
                                            }

                                            // 완전히 동일한 인증서인지 확인
                                            try {
                                                if (Arrays.equals(serverX509.getEncoded(), loadedCert.getEncoded())) {
                                                    Log.d(TAG, "EXACT MATCH: 파일 " + fileName + "이(가) 서버 인증서와 정확히 일치함");
                                                    foundMatch = true;
                                                }
                                            } catch (Exception e) {
                                                Log.e(TAG, "인증서 인코딩 비교 오류", e);
                                            }
                                        }
                                    }
                                }

                                if (!foundMatch) {
                                    Log.d(TAG, "일치하는 인증서 파일을 찾을 수 없음 - 시스템 인증서 사용됨");
                                }

                            } catch (SSLPeerUnverifiedException e) {
                                Log.e(TAG, "서버 인증서 검증 실패", e);
                            }

                            // 기본 호스트명 검증 사용
                            HostnameVerifier defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
                            boolean result = defaultVerifier.verify(hostname, session);
                            Log.d(TAG, "호스트명 검증 결과 >> " + result);
                            return result;
                        }
                    })
                    .build();

        } catch (Exception e) {
            Log.e(TAG, "보안 OkHttp 클라이언트 생성 실패", e);

            // 중요: 인증서 로드 실패 시 기본 클라이언트를 반환하지 않고 예외 발생시키기
            throw new RuntimeException("커스텀 인증서 로드 실패", e);
        }
    }
}