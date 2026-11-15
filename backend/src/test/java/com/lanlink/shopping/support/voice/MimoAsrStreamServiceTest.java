package com.lanlink.shopping.support.voice;

import com.lanlink.shopping.support.media.SupportMediaPerceptionClient;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MimoAsrStreamServiceTest {

    @Test
    void pcm16ToWavHeaderFormat() {
        byte[] pcm = new byte[3200]; // 100ms of 16kHz 16-bit mono
        byte[] wav = MimoAsrStreamService.pcm16ToWav(pcm, 16000, 1);

        assertEquals(3200 + 44, wav.length);
        assertEquals('R', (char) wav[0]);
        assertEquals('I', (char) wav[1]);
        assertEquals('F', (char) wav[2]);
        assertEquals('F', (char) wav[3]);

        assertEquals('W', (char) wav[8]);
        assertEquals('A', (char) wav[9]);
        assertEquals('V', (char) wav[10]);
        assertEquals('E', (char) wav[11]);

        ByteBuffer buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(16000, buf.getInt(24)); // sampleRate
        assertEquals(32000, buf.getInt(28)); // byteRate = 16000 * 1 * 2
        assertEquals(16, buf.getShort(34));  // bitsPerSample
    }

    @Test
    void transcribePcmDelegatesToPerceptionClient() {
        SupportMediaPerceptionClient perceptionClient = mock(SupportMediaPerceptionClient.class);
        when(perceptionClient.asr(any(), eq("audio/wav")))
                .thenReturn(Optional.of(new SupportMediaPerceptionClient.AsrResult(
                        "请问发票在哪里开？", "mimo", "mimo-v2.5-asr", null)));

        MimoAsrStreamService service = new MimoAsrStreamService(perceptionClient);
        byte[] pcm = new byte[3200];
        Optional<String> result = service.transcribePcm(pcm);

        assertTrue(result.isPresent());
        assertEquals("请问发票在哪里开？", result.get());
    }
}
