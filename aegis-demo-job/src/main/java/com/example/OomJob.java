package com.example;

import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;

import java.util.ArrayList;
import java.util.List;

public class OomJob implements AegisJob<String> {
    public OomJob() {}
    public OomJob(String[] args) {}

    @Override
    public String execute(JobContext context) throws Exception {
        System.out.println("OomJob starting. Allocating memory until crash...");
        List<byte[]> list = new ArrayList<>();
        while (true) {
            list.add(new byte[1024 * 1024]); // 1MB per iteration
            Thread.sleep(10);
        }
    }

    
    
}
