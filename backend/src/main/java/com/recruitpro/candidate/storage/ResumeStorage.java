package com.recruitpro.candidate.storage;

import java.io.IOException;

public interface ResumeStorage {
    void store(String storageKey, byte[] content) throws IOException;
    byte[] load(String storageKey) throws IOException;
    void delete(String storageKey) throws IOException;
}
