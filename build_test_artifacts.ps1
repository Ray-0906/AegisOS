$ErrorActionPreference = "Stop"

$cp = "aegis-cli/target/aegis.jar"
if (-not (Test-Path $cp)) {
    Write-Host "Please build AegisOS first: mvn clean package"
    exit 1
}

$buildDir = "build_artifacts"
if (Test-Path $buildDir) { Remove-Item -Recurse -Force $buildDir }
New-Item -ItemType Directory -Path $buildDir | Out-Null

function Build-Artifact {
    param(
        [string]$Name,
        [string]$JavaSource
    )
    $srcDir = "$buildDir/$Name/src/com/example"
    $outDir = "$buildDir/$Name/out"
    New-Item -ItemType Directory -Path $srcDir -Force | Out-Null
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
    
    # Write java files. Split by public class boundaries
    $JavaSource -split "(?=public class)" | Where-Object { $_.Trim() -ne "" } | ForEach-Object {
        if ($_ -match "public class (\w+)") {
            $className = $matches[1]
            $fileContent = "package com.example;`n" +
                           "import com.aegisos.runtime.AegisJob;`n" +
                           "import com.aegisos.runtime.JobContext;`n" +
                           $_
            Set-Content -Path "$srcDir/$className.java" -Value $fileContent
        }
    }

    # Compile
    $srcFiles = Get-ChildItem -Path $srcDir -Filter *.java | Select-Object -ExpandProperty FullName
    & javac -cp $cp -d $outDir $srcFiles
    if ($LASTEXITCODE -ne 0) { throw "Compilation failed for $Name" }

    # Jar
    $jarPath = "$buildDir/$Name.jar"
    Set-Location $outDir
    & jar cf "../../$Name.jar" .
    Set-Location ../../..
    Write-Host "Built $Name.jar"
}

# Artifact A (Cache Rehydration)
Build-Artifact "ArtifactA" @"
public class JobA implements AegisJob<Boolean> {
    public Boolean execute(JobContext ctx) {
        System.out.println("ArtifactA executed");
        return true;
    }
}
"@

# Artifact B (Restart Persistence)
Build-Artifact "ArtifactB" @"
public class JobB implements AegisJob<Boolean> {
    public Boolean execute(JobContext ctx) {
        System.out.println("ArtifactB executed");
        return true;
    }
}
"@

# Artifact C (Cold Node)
Build-Artifact "ArtifactC" @"
public class JobC implements AegisJob<Boolean> {
    public Boolean execute(JobContext ctx) {
        System.out.println("ArtifactC executed");
        return true;
    }
}
"@

# Artifact D_v1 (Versioning)
Build-Artifact "ArtifactD_v1" @"
public class JobD implements AegisJob<String> {
    public String execute(JobContext ctx) {
        System.out.println("ArtifactD version 1");
        return "v1";
    }
}
"@

# Artifact D_v2 (Versioning)
Build-Artifact "ArtifactD_v2" @"
public class JobD implements AegisJob<String> {
    public String execute(JobContext ctx) {
        System.out.println("ArtifactD version 2");
        return "v2";
    }
}
"@

# Artifact E_1 (ClassLoader Isolation)
Build-Artifact "ArtifactE_1" @"
public class Util {
    public static String getVersion() { return "Impl-1"; }
}
public class JobE implements AegisJob<String> {
    public String execute(JobContext ctx) {
        String ver = Util.getVersion();
        System.out.println("JobE running with: " + ver);
        // Sleep to allow concurrent execution
        try { Thread.sleep(5000); } catch (Exception e) {}
        System.out.println("JobE finishing with: " + Util.getVersion());
        return Util.getVersion();
    }
}
"@

# Artifact E_2 (ClassLoader Isolation)
Build-Artifact "ArtifactE_2" @"
public class Util {
    public static String getVersion() { return "Impl-2"; }
}
public class JobE implements AegisJob<String> {
    public String execute(JobContext ctx) {
        String ver = Util.getVersion();
        System.out.println("JobE running with: " + ver);
        try { Thread.sleep(5000); } catch (Exception e) {}
        System.out.println("JobE finishing with: " + Util.getVersion());
        return Util.getVersion();
    }
}
"@

# Artifact F (Deletion During Execution)
Build-Artifact "ArtifactF" @"
public class JobF implements AegisJob<Boolean> {
    public Boolean execute(JobContext ctx) {
        System.out.println("ArtifactF running...");
        try { Thread.sleep(10000); } catch (Exception e) {}
        System.out.println("ArtifactF completed!");
        return true;
    }
}
"@

# Artifact G_v1 (Concurrent Version Race)
Build-Artifact "ArtifactG_v1" @"
public class JobG implements AegisJob<String> {
    public String execute(JobContext ctx) {
        System.out.println("ArtifactG v1 running...");
        try { Thread.sleep(8000); } catch (Exception e) {}
        System.out.println("ArtifactG v1 completing...");
        return "v1";
    }
}
"@

# Artifact G_v2 (Concurrent Version Race)
Build-Artifact "ArtifactG_v2" @"
public class JobG implements AegisJob<String> {
    public String execute(JobContext ctx) {
        System.out.println("ArtifactG v2 running...");
        try { Thread.sleep(8000); } catch (Exception e) {}
        System.out.println("ArtifactG v2 completing...");
        return "v2";
    }
}
"@

Write-Host "All artifacts built successfully."
