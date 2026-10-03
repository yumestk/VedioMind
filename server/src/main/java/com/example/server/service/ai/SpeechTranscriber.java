package com.example.server.service.ai;

import java.io.File;

public interface SpeechTranscriber {

    TranscriptionResult transcribe(File audioFile);
}
