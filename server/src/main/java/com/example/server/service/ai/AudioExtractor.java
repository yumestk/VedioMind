package com.example.server.service.ai;

import java.io.File;

public interface AudioExtractor {

    File extract(String videoUrl);
}
