param(
    [Parameter(Position = 0)]
    [ValidateSet("server", "client", "auto")]
    [string]$Mode,

    [string]$Server,
    [int]$Port = 5201,
    [string]$Config = "config.ini",
    [string]$Tests = "all",
    [ValidateSet("upload", "download", "both")]
    [string]$Direction = "both",
    [int]$Repeat = 1,
    [double]$Timeout = 10,
    [string]$Output,
    [switch]$Version,
    [switch]$Help
)

$VersionValue = "1.04"
$BufferSize = 65536
$SmallFileSize = 1024
$SmallFileCount = 200
$MediumFileSize = 20MB
$LargeFileSize = 300MB

$CmdPing = [System.Text.Encoding]::ASCII.GetBytes("PING")
$CmdPong = [System.Text.Encoding]::ASCII.GetBytes("PONG")
$CmdUpload = [System.Text.Encoding]::ASCII.GetBytes("UPLD")
$CmdDownload = [System.Text.Encoding]::ASCII.GetBytes("DNLD")
$CmdFile = [System.Text.Encoding]::ASCII.GetBytes("FILE")
$CmdDone = [System.Text.Encoding]::ASCII.GetBytes("DONE")
$CmdBye = [System.Text.Encoding]::ASCII.GetBytes("BYE_")

function Show-Help {
    @"
Usage:
  speedtest.bat server [--port 5201]
  speedtest.bat client --server <ip> [--port 5201] [--tests all] [--direction both] [--repeat 1] [--timeout 10] [--output result.json]
  speedtest.bat auto [--config config.ini] [--output result.csv]

Options:
  --version                 Affiche la version
  --tests small,medium      Types: small, medium, large, all
  --direction both          upload | download | both
  --repeat 3                Nombre de passages complets
  --timeout 10              Timeout socket en secondes
  --output out.json|out.csv Export résultats
"@
}

function Get-CmdString([byte[]]$cmdBytes) {
    return [System.Text.Encoding]::ASCII.GetString($cmdBytes)
}

function Send-Cmd($stream, [byte[]]$cmd, [byte[]]$payload) {
    if (-not $payload) { $payload = [byte[]]::new(0) }
    $header = [byte[]]::new(12)
    [Array]::Copy($cmd, 0, $header, 0, 4)
    $lenBytes = [BitConverter]::GetBytes([UInt64]$payload.Length)
    if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($lenBytes) }
    [Array]::Copy($lenBytes, 0, $header, 4, 8)
    $stream.Write($header, 0, $header.Length)
    if ($payload.Length -gt 0) { $stream.Write($payload, 0, $payload.Length) }
}

function Read-Exactly($stream, [int]$count) {
    $buffer = [byte[]]::new($count)
    $offset = 0
    while ($offset -lt $count) {
        $read = $stream.Read($buffer, $offset, $count - $offset)
        if ($read -le 0) { throw "Connexion fermée prématurément." }
        $offset += $read
    }
    return $buffer
}

function Receive-Cmd($stream) {
    $header = Read-Exactly $stream 12
    $cmd = [byte[]]::new(4)
    [Array]::Copy($header, 0, $cmd, 0, 4)

    $lenBytes = [byte[]]::new(8)
    [Array]::Copy($header, 4, $lenBytes, 0, 8)
    if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($lenBytes) }
    $length = [BitConverter]::ToUInt64($lenBytes, 0)

    $payload = [byte[]]::new(0)
    if ($length -gt 0) {
        $payload = Read-Exactly $stream ([int]$length)
    }

    return [PSCustomObject]@{
        Cmd = $cmd
        Payload = $payload
        CmdText = Get-CmdString $cmd
    }
}

function Send-SyntheticFile($stream, [long]$size) {
    $sizeBytes = [BitConverter]::GetBytes([UInt64]$size)
    if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($sizeBytes) }
    Send-Cmd $stream $CmdFile $sizeBytes

    $chunk = [byte[]]::new($BufferSize)
    $remaining = $size
    while ($remaining -gt 0) {
        $toSend = [Math]::Min($BufferSize, [int]$remaining)
        $stream.Write($chunk, 0, $toSend)
        $remaining -= $toSend
    }
}

function Receive-FileData($stream, [long]$size) {
    $remaining = $size
    $chunk = [byte[]]::new($BufferSize)
    while ($remaining -gt 0) {
        $toRead = [Math]::Min($BufferSize, [int]$remaining)
        $read = $stream.Read($chunk, 0, $toRead)
        if ($read -le 0) { throw "Connexion fermée pendant réception de fichier." }
        $remaining -= $read
    }
}

function Get-TestMatrix([string]$testSpec) {
    if ($testSpec.ToLower().Trim() -eq "all") { return @("small", "medium", "large") }
    $vals = $testSpec.Split(",") | ForEach-Object { $_.Trim().ToLower() } | Where-Object { $_ -in @("small", "medium", "large") }
    if ($vals.Count -eq 0) { return @("small", "medium", "large") }
    return $vals
}

function New-Result($direction, $fileType, $fileCount, $totalBytes, $elapsed, $pingMs, $repeatIndex, $target) {
    $mbps = if ($elapsed -le 0) { 0 } else { [Math]::Round(($totalBytes * 8) / ($elapsed * 1000000), 3) }
    $mibS = if ($elapsed -le 0) { 0 } else { [Math]::Round($totalBytes / ($elapsed * 1048576), 3) }
    [PSCustomObject]@{
        target = $target
        direction = $direction
        file_type = $fileType
        repeat_index = $repeatIndex
        file_count = $fileCount
        total_bytes = $totalBytes
        elapsed_seconds = [Math]::Round($elapsed, 6)
        throughput_mbps = $mbps
        throughput_mib_s = $mibS
        ping_ms = [Math]::Round($pingMs, 3)
    }
}

function Invoke-TcpPing($server, $port, $timeoutSec) {
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $iar = $client.BeginConnect($server, $port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne([TimeSpan]::FromSeconds($timeoutSec))) {
            $client.Close()
            return -1
        }
        $client.EndConnect($iar)
        $stream = $client.GetStream()
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        Send-Cmd $stream $CmdPing ([byte[]]::new(0))
        $resp = Receive-Cmd $stream
        $sw.Stop()
        Send-Cmd $stream $CmdBye ([byte[]]::new(0))
        $client.Close()
        if ($resp.CmdText -eq "PONG") { return [Math]::Round($sw.Elapsed.TotalMilliseconds, 3) }
    } catch {
    }
    return -1
}

function Start-Server([int]$port) {
    $listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $port)
    $listener.Start()
    Write-Host "[SERVEUR] En écoute sur 0.0.0.0:$port"

    while ($true) {
        $client = $listener.AcceptTcpClient()
        $stream = $client.GetStream()
        try {
            while ($true) {
                $req = Receive-Cmd $stream
                switch ($req.CmdText) {
                    "BYE_" { break }
                    "PING" { Send-Cmd $stream $CmdPong ([byte[]]::new(0)); continue }
                    "UPLD" {
                        $pingStart = Receive-Cmd $stream
                        if ($pingStart.CmdText -ne "PING") { break }
                        Send-Cmd $stream $CmdPong ([byte[]]::new(0))

                        while ($true) {
                            $x = Receive-Cmd $stream
                            if ($x.CmdText -eq "DONE") { break }
                            if ($x.CmdText -eq "FILE") {
                                $sizeBytes = $x.Payload
                                if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($sizeBytes) }
                                $size = [BitConverter]::ToUInt64($sizeBytes, 0)
                                Receive-FileData $stream $size
                            }
                        }

                        $pingEnd = Receive-Cmd $stream
                        if ($pingEnd.CmdText -eq "PING") { Send-Cmd $stream $CmdPong ([byte[]]::new(0)) }
                        Send-Cmd $stream ([System.Text.Encoding]::ASCII.GetBytes("RSLT")) ([System.Text.Encoding]::UTF8.GetBytes("{}"))
                        continue
                    }
                    "DNLD" {
                        $testType = [System.Text.Encoding]::UTF8.GetString($req.Payload)
                        $pingStart = Receive-Cmd $stream
                        if ($pingStart.CmdText -ne "PING") { break }
                        Send-Cmd $stream $CmdPong ([byte[]]::new(0))

                        switch ($testType) {
                            "small" { 1..$SmallFileCount | ForEach-Object { Send-SyntheticFile $stream $SmallFileSize } }
                            "medium" { Send-SyntheticFile $stream $MediumFileSize }
                            "large" { Send-SyntheticFile $stream $LargeFileSize }
                        }

                        Send-Cmd $stream $CmdDone ([byte[]]::new(0))
                        $pingEnd = Receive-Cmd $stream
                        if ($pingEnd.CmdText -eq "PING") { Send-Cmd $stream $CmdPong ([byte[]]::new(0)) }
                        continue
                    }
                    default {
                        Send-Cmd $stream ([System.Text.Encoding]::ASCII.GetBytes("ERRR")) ([System.Text.Encoding]::UTF8.GetBytes("Commande inconnue"))
                    }
                }
            }
        } catch {
        } finally {
            $stream.Close()
            $client.Close()
        }
    }
}

function Invoke-ClientTests($server, $port, $tests, $direction, $repeat, $timeout) {
    $results = @()
    $ping = Invoke-TcpPing $server $port $timeout
    if ($ping -lt 0) {
        Write-Host "[AVERTISSEMENT] Impossible de joindre $server`:$port"
        return $results
    }

    Write-Host "Latence TCP: $ping ms"

    for ($i = 1; $i -le $repeat; $i++) {
        if ($repeat -gt 1) { Write-Host "--- Passage $i/$repeat ---" }

        foreach ($testType in $tests) {
            if ($direction -in @("upload", "both")) {
                $client = New-Object System.Net.Sockets.TcpClient
                $client.ReceiveTimeout = [int]($timeout * 1000)
                $client.SendTimeout = [int]($timeout * 1000)
                $client.Connect($server, $port)
                $stream = $client.GetStream()
                Send-Cmd $stream $CmdUpload ([System.Text.Encoding]::UTF8.GetBytes($testType))

                $startPing = [System.Diagnostics.Stopwatch]::StartNew()
                Send-Cmd $stream $CmdPing ([byte[]]::new(0))
                [void](Receive-Cmd $stream)
                $startPing.Stop()

                $sw = [System.Diagnostics.Stopwatch]::StartNew()
                $totalBytes = 0
                $fileCount = 0
                switch ($testType) {
                    "small" {
                        1..$SmallFileCount | ForEach-Object { Send-SyntheticFile $stream $SmallFileSize }
                        $totalBytes = $SmallFileSize * $SmallFileCount
                        $fileCount = $SmallFileCount
                    }
                    "medium" {
                        Send-SyntheticFile $stream $MediumFileSize
                        $totalBytes = $MediumFileSize
                        $fileCount = 1
                    }
                    "large" {
                        Send-SyntheticFile $stream $LargeFileSize
                        $totalBytes = $LargeFileSize
                        $fileCount = 1
                    }
                }

                Send-Cmd $stream $CmdDone ([byte[]]::new(0))
                Send-Cmd $stream $CmdPing ([byte[]]::new(0))
                [void](Receive-Cmd $stream)
                $sw.Stop()

                try { [void](Receive-Cmd $stream) } catch {}
                Send-Cmd $stream $CmdBye ([byte[]]::new(0))
                $stream.Close(); $client.Close()

                $r = New-Result "upload" $testType $fileCount $totalBytes $sw.Elapsed.TotalSeconds $startPing.Elapsed.TotalMilliseconds $i $server
                $results += $r
                Write-Host ("UPLOAD {0} -> {1} Mbps" -f $testType, $r.throughput_mbps)
            }

            if ($direction -in @("download", "both")) {
                $client = New-Object System.Net.Sockets.TcpClient
                $client.ReceiveTimeout = [int]($timeout * 1000)
                $client.SendTimeout = [int]($timeout * 1000)
                $client.Connect($server, $port)
                $stream = $client.GetStream()
                Send-Cmd $stream $CmdDownload ([System.Text.Encoding]::UTF8.GetBytes($testType))

                $startPing = [System.Diagnostics.Stopwatch]::StartNew()
                Send-Cmd $stream $CmdPing ([byte[]]::new(0))
                [void](Receive-Cmd $stream)
                $startPing.Stop()

                $sw = [System.Diagnostics.Stopwatch]::StartNew()
                $totalBytes = 0
                $fileCount = 0
                while ($true) {
                    $resp = Receive-Cmd $stream
                    if ($resp.CmdText -eq "DONE") { break }
                    if ($resp.CmdText -eq "FILE") {
                        $sizeBytes = $resp.Payload
                        if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($sizeBytes) }
                        $size = [BitConverter]::ToUInt64($sizeBytes, 0)
                        Receive-FileData $stream $size
                        $totalBytes += $size
                        $fileCount += 1
                    }
                }

                Send-Cmd $stream $CmdPing ([byte[]]::new(0))
                [void](Receive-Cmd $stream)
                $sw.Stop()

                Send-Cmd $stream $CmdBye ([byte[]]::new(0))
                $stream.Close(); $client.Close()

                $r = New-Result "download" $testType $fileCount $totalBytes $sw.Elapsed.TotalSeconds $startPing.Elapsed.TotalMilliseconds $i $server
                $results += $r
                Write-Host ("DOWNLOAD {0} -> {1} Mbps" -f $testType, $r.throughput_mbps)
            }
        }
    }

    return $results
}

function Write-Results($results, $outputPath, $mode, $tests, $direction, $repeat, $timeout) {
    if (-not $outputPath) { return }
    if ($results.Count -eq 0) {
        Write-Host "[INFO] Aucun résultat à exporter."
        return
    }

    $ext = [System.IO.Path]::GetExtension($outputPath).ToLower()
    if ($ext -eq ".json") {
        $payload = [PSCustomObject]@{
            metadata = [PSCustomObject]@{
                version = $VersionValue
                mode = $mode
                tests = $tests
                direction = $direction
                repeat = $repeat
                timeout_seconds = $timeout
            }
            results = $results
        }
        $payload | ConvertTo-Json -Depth 6 | Out-File -FilePath $outputPath -Encoding utf8
        Write-Host "[INFO] Export JSON: $outputPath"
    } elseif ($ext -eq ".csv") {
        $results | Export-Csv -Path $outputPath -NoTypeInformation -Encoding UTF8
        Write-Host "[INFO] Export CSV: $outputPath"
    } else {
        throw "Format de sortie non supporté (utiliser .json ou .csv)."
    }
}

function Read-IniSimple([string]$path) {
    if (-not (Test-Path $path)) { throw "Config introuvable: $path" }
    $cfg = @{}
    $section = ""
    foreach ($line in Get-Content $path) {
        $t = $line.Trim()
        if ($t -eq "" -or $t.StartsWith("#") -or $t.StartsWith(";")) { continue }
        if ($t -match "^\[(.+)\]$") {
            $section = $Matches[1].ToLower()
            if (-not $cfg.ContainsKey($section)) { $cfg[$section] = @{} }
            continue
        }
        if ($t -match "^([^=]+)=(.*)$") {
            $k = $Matches[1].Trim().ToLower()
            $v = $Matches[2].Trim()
            if ($section -ne "") { $cfg[$section][$k] = $v }
        }
    }
    return $cfg
}

if ($Version) {
    Write-Host "speed-intranet-windows v$VersionValue"
    exit 0
}

if ($Help -or -not $Mode) {
    Show-Help
    exit 0
}

if ($Repeat -le 0) { throw "--repeat doit être > 0" }
if ($Timeout -le 0) { throw "--timeout doit être > 0" }

$testsList = Get-TestMatrix $Tests

switch ($Mode) {
    "server" {
        Start-Server -port $Port
    }
    "client" {
        if (-not $Server) { throw "--server est obligatoire en mode client." }
        $results = Invoke-ClientTests -server $Server -port $Port -tests $testsList -direction $Direction -repeat $Repeat -timeout $Timeout
        Write-Results -results $results -outputPath $Output -mode "client" -tests $testsList -direction $Direction -repeat $Repeat -timeout $Timeout
    }
    "auto" {
        $cfg = Read-IniSimple $Config
        $portVal = if ($cfg.ContainsKey("network") -and $cfg["network"].ContainsKey("port")) { [int]$cfg["network"]["port"] } else { $Port }
        $timeoutVal = if ($cfg.ContainsKey("network") -and $cfg["network"].ContainsKey("timeout")) { [double]$cfg["network"]["timeout"] } else { $Timeout }
        $dirVal = if ($cfg.ContainsKey("tests") -and $cfg["tests"].ContainsKey("direction")) { $cfg["tests"]["direction"].ToLower() } else { $Direction }
        $repVal = if ($cfg.ContainsKey("tests") -and $cfg["tests"].ContainsKey("repeat")) { [int]$cfg["tests"]["repeat"] } else { $Repeat }
        $tVal = if ($cfg.ContainsKey("tests") -and $cfg["tests"].ContainsKey("test_types")) { $cfg["tests"]["test_types"] } else { $Tests }
        $terms = if ($cfg.ContainsKey("network") -and $cfg["network"].ContainsKey("terminals")) { $cfg["network"]["terminals"].Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ } } else { @() }

        if ($terms.Count -eq 0) { throw "Aucun terminal trouvé dans config.ini ([network] terminals=...)" }
        $testsAuto = Get-TestMatrix $tVal
        $allResults = @()
        foreach ($ip in $terms) {
            Write-Host "=== Test terminal $ip ==="
            $allResults += Invoke-ClientTests -server $ip -port $portVal -tests $testsAuto -direction $dirVal -repeat $repVal -timeout $timeoutVal
        }
        Write-Results -results $allResults -outputPath $Output -mode "auto" -tests $testsAuto -direction $dirVal -repeat $repVal -timeout $timeoutVal
    }
}



