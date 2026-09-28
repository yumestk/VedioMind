package com.example.server.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class YtDlpUtils {

    @Value("${tool.ytdlp.path}")
    private String ytDlpPath;

    @Value("${tool.ffmpeg.dir}")
    private String ffmpegDir;

    // 代理地址，国内下载 YouTube 必须配置。格式: http://127.0.0.1:7890
    @Value("${tool.ytdlp.proxy:}")
    private String proxy;

    public File downloadVideo(String url) throws Exception {
        String tempDir = System.getProperty("java.io.tmpdir");
        String outputName = UUID.randomUUID().toString() + ".mp4";
        String outputPath = tempDir + File.separator + outputName;

        System.out.println("⬇️ [yt-dlp] 开始下载 (智能模式): " + url);

        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);


        // 限制最高 720p，避免下载 GB 级文件；ASR 不需要高画质
        command.add("-f");
        command.add("bestvideo[height<=720]+bestaudio/best[height<=720]/best");

        //伪装头 (保留，防止直接被 ban)
        command.add("--user-agent");
        command.add("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
        command.add("--referer");
        command.add("https://www.bilibili.com/");

        // 国内环境走代理下载 YouTube
        if (proxy != null && !proxy.isBlank()) {
            command.add("--proxy");
            command.add(proxy);
        }

        //强制转码 mp4 (这是唯一的硬性要求)
        command.add("--recode-video");
        command.add("mp4");

        command.add("--ffmpeg-location");
        command.add(ffmpegDir);

        command.add("-o");
        command.add(outputPath);

        //忽略证书和播放列表
        command.add("--no-check-certificate");
        command.add("--no-playlist");

        // 加超时兜底，防止无限卡住（socket 超时 30s，总重试 3 次）
        command.add("--socket-timeout");
        command.add("30");
        command.add("--retries");
        command.add("3");

        command.add(url);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder logs = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                //打印yt-dlp的关键日志，方便调试
                if (line.contains("ERROR") || line.contains("Downloading") || line.contains("[Merger]")) {
                    System.out.println("cmd > " + line);
                }
                logs.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            //如果还是失败抛出异常，前端会显示红色报错
            throw new RuntimeException("yt-dlp 下载失败: " + logs.toString());
        }

        File downloadedFile = new File(outputPath);
        if (!downloadedFile.exists()) {
            throw new RuntimeException("下载显示成功但文件未生成");
        }

        System.out.println("✅ [yt-dlp] 下载完成: " + (downloadedFile.length() / 1024) + "KB");
        return downloadedFile;
    }
}