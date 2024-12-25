package com.example.MigrosBackend.service.global;

import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class FileService {
    public Path writeFileToDisk(byte[] fileDataAsBytes,
                                String fileName,
                                String directory) throws IOException {
        Path directoryPath = Paths.get(directory);
        Path filePath = directoryPath.resolve(fileName);
        Files.write(filePath, fileDataAsBytes);

        return filePath;
    }
}
