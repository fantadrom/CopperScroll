param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [ValidateRange(1,100)][int]$Repeats = 20,
    [ValidateRange(1,100)][int]$ColdRepeats = 1
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$gradle = Join-Path $repo 'gradlew.bat'
$output = Join-Path $repo ('work/p2-benchmarks/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$previousSerial = $env:ANDROID_SERIAL
New-Item -ItemType Directory -Path $output -Force | Out-Null
Push-Location $repo
try {
    $env:ANDROID_SERIAL = $Serial
    foreach ($memory in @(16384,32768,65536)) {
        foreach ($iterations in @(2,3,4)) {
            foreach ($slots in @(1,2)) {
              for ($coldRun = 1; $coldRun -le $ColdRepeats; $coldRun++) {
                $point = Join-Path $output "m$memory-t$iterations-s$slots-cold$coldRun"
                New-Item -ItemType Directory -Path $point | Out-Null
                $warmSamples = if ($coldRun -eq 1) { $Repeats } else { 0 }
                # Each connected invocation starts a new instrumentation process: one cold + N warm samples.
                # Windows PowerShell5.1 represents redirected native stderr as ErrorRecord; warnings
                # must not abort a successful Gradle command. The native exit code remains mandatory.
                $ErrorActionPreference = 'Continue'
                & $gradle '-Pp2KdfBenchmark=true' "-Pp2BenchmarkMemoryKiB=$memory" `
                    "-Pp2BenchmarkIterations=$iterations" "-Pp2BenchmarkSlots=$slots" `
                    "-Pp2BenchmarkRepeats=$warmSamples" ':app:p2KdfBenchmarkCheck' '--console=plain' 2>&1 |
                    Tee-Object -FilePath (Join-Path $point 'gradle.txt')
                $nativeExit = $LASTEXITCODE
                $ErrorActionPreference = 'Stop'
                if ($nativeExit -ne 0) { throw "Benchmark command failed; inspect $point" }
                Copy-Item 'app/build/reports/p2-benchmark' (Join-Path $point 'diagnostics') -Recurse
                Copy-Item 'app/build/outputs/androidTest-results/connected/p1Release' (Join-Path $point 'results') -Recurse
                if (Test-Path 'app/build/outputs/connected_android_test_additional_output/p1ReleaseAndroidTest/connected') {
                    Copy-Item 'app/build/outputs/connected_android_test_additional_output/p1ReleaseAndroidTest/connected' `
                        (Join-Path $point 'metrics') -Recurse
                }
              }
            }
        }
    }
    Write-Output "18 profile/slot points, $ColdRepeats cold processes and $Repeats warm samples each. Metrics: $output"
} finally {
    $env:ANDROID_SERIAL = $previousSerial
    Pop-Location
}
