package com.aegisos.examples;

import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;

public class HelloAegis implements AegisJob<String> {
    @Override
    public String execute(JobContext ctx) throws Exception {
        System.out.println("Hello AegisOS! Running from Node: " + ctx.executingNode());
        return "Hello World";
    }
}
