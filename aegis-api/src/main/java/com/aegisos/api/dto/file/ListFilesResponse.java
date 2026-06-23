package com.aegisos.api.dto.file;

import java.util.List;

public class ListFilesResponse {
    public List<FileInfo> files;

    public ListFilesResponse() {}

    public ListFilesResponse(List<FileInfo> files) {
        this.files = files;
    }

    public static class FileInfo {
        public String name;
        public long size;
        public int chunks;

        public FileInfo() {}

        public FileInfo(String name, long size, int chunks) {
            this.name = name;
            this.size = size;
            this.chunks = chunks;
        }
    }
}
