package com.example.server.service.ai.impl;

import com.example.server.service.ai.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AsrSlicesTest {
    private static final int RATE = 16_000;

    @Test
    void choosesNearbySilenceAndUsesOverlapOnlyAtForcedCuts() {
        assertEquals(1, AsrSlices.plan(600L * RATE, List.of()).slices().size());
        var plan = AsrSlices.plan(5_430L * RATE, List.of(548L * RATE, 580L * RATE));
        assertEquals(10, plan.slices().size());
        assertEquals(new AsrSlices.Cut(548L * RATE, true), plan.cuts().getFirst());
        assertEquals(plan.slices().getFirst().endSample(), plan.slices().get(1).startSample());
        assertEquals(2L * RATE, plan.slices().get(1).endSample() - plan.slices().get(2).startSample());
        assertEquals(plan.samples(), plan.slices().getLast().endSample());
    }

    @Test
    void mergesOutOfOrderAndDeduplicatesOverlappingBoundary() {
        var plan = AsrSlices.plan(1_080L * RATE + 7, List.of());
        var left = plan.slices().getFirst();
        var right = plan.slices().getLast();
        var a = new AsrSlices.Piece(left, List.of(new TranscriptSegmentDraft(539_250, 540_500, "重复。")));
        var b = new AsrSlices.Piece(right, List.of(new TranscriptSegmentDraft(300, 1400, "重复"),
                new TranscriptSegmentDraft(2000, 3000, "下一句")));
        var result = AsrSlices.merge(plan, List.of(b, a)).segments();
        assertEquals(List.of(new TranscriptSegmentDraft(539_250, 540_500, "重复。"),
                new TranscriptSegmentDraft(541_000, 542_000, "下一句")), result);
        var conflict = new AsrSlices.Piece(right, List.of(new TranscriptSegmentDraft(300, 1400, "不同")));
        assertThrows(IllegalStateException.class, () -> AsrSlices.merge(plan, List.of(a, conflict)));
        var invalid = new AsrSlices.Piece(right, List.of(new TranscriptSegmentDraft(0, 650_000, "越界")));
        assertThrows(IllegalStateException.class, () -> AsrSlices.merge(plan, List.of(a, invalid)));
    }

    @Test
    void timestampedSilenceAndInterimEventsAreNotTranscriptSegments() {
        var lines = new java.util.ArrayList<TranscriptSegmentDraft>();
        ParaformerStream.appendSentence(lines, com.alibaba.fastjson2.JSONObject.of(
                "begin_time", 100L, "end_time", 120L, "text", " "));
        ParaformerStream.appendSentence(lines, com.alibaba.fastjson2.JSONObject.of(
                "begin_time", 120L, "text", "interim"));
        ParaformerStream.appendSentence(lines, com.alibaba.fastjson2.JSONObject.of(
                "begin_time", 120L, "end_time", 220L, "text", "final"));
        assertEquals(List.of(new TranscriptSegmentDraft(120, 220, "final")), lines);
    }

    @Test
    void waitsForLocalQuotaAndRetriesOnlyTheSlice() {
        var limits = mock(AiRateLimitService.class);
        doThrow(new AiRateLimitService.AiRateLimitExceededException("wait", 0))
                .doNothing().when(limits).requireProviderRequest(AiRateLimitService.ALIYUN_ASR);
        var transcriber = new AliyunSpeechTranscriber("test", new AiRetryExecutor(limits), 2, "", "wss://unused.test");
        var started = new java.util.concurrent.atomic.AtomicInteger();
        var sends = new java.util.concurrent.atomic.AtomicInteger();
        int result = transcriber.retrySlice(() -> {
            if (sends.incrementAndGet() == 1) throw new AiProviderException("known retryable", null, true, 0);
            return 7;
        }, started::incrementAndGet);
        assertEquals(7, result);
        assertEquals(2, started.get());
        verify(limits, times(3)).requireProviderRequest(AiRateLimitService.ALIYUN_ASR);
    }

    @Test
    void terminalFailureInterruptsOtherActiveSlicesBeforeReturning() {
        var transcriber = new AliyunSpeechTranscriber("test", null, 2, "", "wss://unused.test");
        var plan = AsrSlices.plan(1_080L * RATE, List.of());
        var running = new CountDownLatch(1);
        var released = new AtomicBoolean();
        var failure = assertThrows(IllegalStateException.class, () -> transcriber.runSlices(plan, slice -> {
            if (slice.index() == 0) {
                assertTrue(running.await(5, TimeUnit.SECONDS));
                throw new IllegalStateException("test failure");
            }
            running.countDown();
            try {
                Thread.sleep(30_000);
            } finally {
                released.set(true);
            }
            throw new AssertionError("Expected cancellation");
        }));
        assertTrue(failure.getMessage().contains("slice 1"));
        assertTrue(released.get());
    }
}
