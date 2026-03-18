param(
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

function Replace-FirstMatch {
    param(
        [string]$Path,
        [string]$Pattern,
        [scriptblock]$ReplacementScript
    )

    $content = Get-Content -Path $Path -Raw -Encoding UTF8
    $regex = [regex]::new($Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    $match = $regex.Match($content)
    if (-not $match.Success) {
        throw "Pattern not found in $Path"
    }

    $replacement = & $ReplacementScript $match
    $newContent = $content.Substring(0, $match.Index) + $replacement + $content.Substring($match.Index + $match.Length)
    Set-Content -Path $Path -Value $newContent -Encoding UTF8
}

function Bump-Decimal([string]$value) {
    return ([string]::Format("{0:F2}", ([double]$value + 0.01))).Replace(',', '.')
}

function Bump-Semver([string]$value) {
    $parts = $value.Split('.')
    $major = [int]$parts[0]
    $minor = [int]$parts[1]
    $patch = [int]$parts[2] + 1
    return "$major.$minor.$patch"
}

$decimalVersion = $null
$semverVersion = $null

$decimalTargets = @(
    @{ Path = "java/src/main/java/com/speedintranet/SpeedIntranet.java"; Pattern = 'private\s+static\s+final\s+String\s+VERSION\s*=\s*"(\d+\.\d{2})";'; Format = { param($v) "private static final String VERSION = `"$v`";" } },
    @{ Path = "java/src/main/java/com/speedintranet/SpeedIntranetGui.java"; Pattern = 'frame\s*=\s*new\s+JFrame\("speed-intranet\sv(\d+\.\d{2})"\);'; Format = { param($v) "frame = new JFrame(`"speed-intranet v$v`");" } }
)

foreach ($target in $decimalTargets) {
    $fullPath = Join-Path $RepoRoot $target.Path
    Replace-FirstMatch -Path $fullPath -Pattern $target.Pattern -ReplacementScript {
        param($m)
        if (-not $script:decimalVersion) {
            $script:decimalVersion = Bump-Decimal $m.Groups[1].Value
        }
        return (& $target.Format $script:decimalVersion)
    }
}

$pomPath = Join-Path $RepoRoot "java/pom.xml"
Replace-FirstMatch -Path $pomPath -Pattern '<version>(\d+)\.(\d+)\.(\d+)</version>' -ReplacementScript {
    param($m)
    if (-not $script:semverVersion) {
        $script:semverVersion = Bump-Semver "$($m.Groups[1].Value).$($m.Groups[2].Value).$($m.Groups[3].Value)"
    }
    return "<version>$script:semverVersion</version>"
}

$docTargets = @(
    @{ Path = "README.md"; Pattern = 'speed-intranet-java8-(\d+\.\d+\.\d+)\.jar'; Format = { param($v) "speed-intranet-java8-$v.jar" } },
    @{ Path = "java/README.md"; Pattern = 'speed-intranet-java8-(\d+\.\d+\.\d+)\.jar'; Format = { param($v) "speed-intranet-java8-$v.jar" } }
)

foreach ($target in $docTargets) {
    $fullPath = Join-Path $RepoRoot $target.Path
    Replace-FirstMatch -Path $fullPath -Pattern $target.Pattern -ReplacementScript {
        param($m)
        if (-not $script:semverVersion) {
            throw "Semver version is not initialized"
        }
        return (& $target.Format $script:semverVersion)
    }
}

Write-Host "Version incremented: decimal=$decimalVersion, semver=$semverVersion"
