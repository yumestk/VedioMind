package com.example.server.service.ai.impl;

import com.example.server.service.ai.TranscriptSegmentDraft;
import com.example.server.service.ai.TranscriptionResult;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class AsrSlices {
    static final int SAMPLE_RATE = 16_000;
    private static final int SAMPLE_BYTES = 2;
    private static final long SHORT_LIMIT = 600L * SAMPLE_RATE;
    private static final long TARGET = 540L * SAMPLE_RATE;
    private static final long SEARCH_RADIUS = 15L * SAMPLE_RATE;
    private static final long OVERLAP = SAMPLE_RATE;
    private static final Pattern SILENCE = Pattern.compile("silence_end: ([0-9.]+).*silence_duration: ([0-9.]+)");

    private AsrSlices() {}

    public static Plan plan(Path pcm, String ffmpeg) throws IOException, InterruptedException {
        long bytes = Files.size(pcm);
        if (bytes == 0 || bytes % SAMPLE_BYTES != 0) throw new IOException("Invalid mono s16le PCM length");
        long samples = bytes / SAMPLE_BYTES;
        if (samples <= SHORT_LIMIT) return plan(samples, List.of());
        var log = Files.createTempFile("asr-silence-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(ffmpeg, "-hide_banner", "-nostats", "-f", "s16le",
                    "-ar", "16000", "-ac", "1", "-i", pcm.toString(),
                    "-af", "silencedetect=noise=-28dB:d=0.25", "-f", "null", "-")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(log.toFile()).start();
            if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("FFmpeg could not detect ASR silence");
            }
            var silences = new ArrayList<Long>();
            try (var lines = Files.lines(log)) {
                lines.forEach(line -> {
                    var match = SILENCE.matcher(line);
                    if (match.find()) {
                        double midpoint = Double.parseDouble(match.group(1)) - Double.parseDouble(match.group(2)) / 2;
                        silences.add(Math.round(midpoint * SAMPLE_RATE));
                    }
                });
            }
            return plan(samples, silences);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly().onExit().join();
            Files.deleteIfExists(log);
        }
    }

    static Plan plan(long samples, List<Long> silences) {
        int n = samples <= SHORT_LIMIT ? 1 : Math.max(2, Math.toIntExact(Math.round((double) samples / TARGET)));
        var boundaries = new ArrayList<Cut>();
        for (int i = 1; i < n; i++) {
            long target = Math.round((double) samples * i / n);
            var nearest = silences.stream()
                    .filter(s -> s > 0 && s < samples && Math.abs(s - target) <= SEARCH_RADIUS)
                    .min(Comparator.<Long>comparingLong(s -> Math.abs(s - target)).thenComparingLong(s -> s));
            boundaries.add(new Cut(nearest.orElse(target), nearest.isPresent()));
        }
        var pieces = new ArrayList<Slice>();
        for (int i = 0; i < n; i++) {
            Cut before = i == 0 ? new Cut(0, true) : boundaries.get(i - 1);
            Cut after = i == n - 1 ? new Cut(samples, true) : boundaries.get(i);
            pieces.add(new Slice(i, before.sample() - (before.silent() ? 0 : OVERLAP),
                    after.sample() + (after.silent() ? 0 : OVERLAP)));
        }
        return new Plan(samples, List.copyOf(boundaries), List.copyOf(pieces));
    }

    static void copy(Path pcm, Path output, Slice slice) throws IOException {
        try (var source = FileChannel.open(pcm, StandardOpenOption.READ);
             var target = FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            long begin = slice.startSample() * SAMPLE_BYTES;
            long left = (slice.endSample() - slice.startSample()) * SAMPLE_BYTES;
            while (left > 0) {
                long moved = source.transferTo(begin, left, target);
                if (moved == 0) throw new IOException("Unexpected end of PCM");
                left -= moved;
                begin += moved;
            }
        }
    }

    static TranscriptionResult merge(Plan plan, List<Piece> pieces) {
        if (pieces.size() != plan.slices().size() || !pieces.stream().map(Piece::slice).sorted(
                Comparator.comparingInt(Slice::index)).toList().equals(plan.slices())) {
            throw new IllegalStateException("Incomplete or duplicate ASR slices");
        }
        record Timed(long startSample, long endSample, Slice slice, String text) {}
        var all = new ArrayList<Timed>();
        for (var piece : pieces) {
            for (var line : piece.lines()) {
                long start = piece.slice().startSample() + Math.multiplyExact(line.startMs(), SAMPLE_RATE / 1000);
                long end = piece.slice().startSample() + Math.multiplyExact(line.endMs(), SAMPLE_RATE / 1000);
                if (0 < end - piece.slice().endSample() && end - piece.slice().endSample() < SAMPLE_RATE / 1000) {
                    end = piece.slice().endSample();
                }
                if (start < piece.slice().startSample() || millis(start) >= millis(end) || end > piece.slice().endSample()) {
                    throw new IllegalStateException("ASR slice " + (piece.slice().index() + 1) + " returned an out-of-range sentence");
                }
                all.add(new Timed(start, end, piece.slice(), line.text()));
            }
        }
        all.sort(Comparator.comparingLong(Timed::startSample).thenComparingLong(Timed::endSample));
        var merged = new ArrayList<Timed>();
        for (var next : all) {
            if (!merged.isEmpty() && next.startSample() < merged.getLast().endSample()) {
                var previous = merged.getLast();
                boolean overlappingSlices = previous.slice().index() != next.slice().index()
                        && previous.slice().startSample() < next.slice().endSample()
                        && next.slice().startSample() < previous.slice().endSample();
                String a = normalize(previous.text());
                String b = normalize(next.text());
                if (overlappingSlices && !a.isEmpty() && a.equals(b)) {
                    if (next.endSample() - next.startSample() > previous.endSample() - previous.startSample()) {
                        merged.set(merged.size() - 1, next);
                    }
                    continue;
                }
                throw new IllegalStateException("Unresolved ASR sentence overlap at " + millis(next.startSample()) + " ms");
            }
            merged.add(next);
        }
        return new TranscriptionResult(merged.stream().map(t -> new TranscriptSegmentDraft(
                millis(t.startSample()), millis(t.endSample()), t.text())).toList());
    }

    public static String normalize(String text) {
        return text.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\p{P}\\p{IsWhite_Space}]+", "");
    }

    public static long millis(long sample) {
        return sample / (SAMPLE_RATE / 1000);
    }

    public record Cut(long sample, boolean silent) {}
    public record Slice(int index, long startSample, long endSample) {}
    public record Plan(long samples, List<Cut> cuts, List<Slice> slices) {}
    record Piece(Slice slice, List<TranscriptSegmentDraft> lines) {}
}
