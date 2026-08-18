<#
.SYNOPSIS
    Pins the `remote` line of OpenVPN config files to a real IP address,
    resolved over DNS-over-HTTPS.

.DESCRIPTION
    A downloaded .ovpn file points at a hostname:

        remote de-fra.prod.surfshark.com 1443 tcp

    On a censored line that hostname is the weak link. The resolver answers
    with a forged address - 10.10.34.35 and friends, a machine on your own
    LAN - and OpenVPN dutifully dials it and gets nowhere. It is not the
    tunnel failing; it never left the building.

    This script resolves each remote hostname over DoH instead, throws away
    any answer that is obviously forged, and writes the config back out with
    the address written in literally. Nothing then depends on your resolver
    at connect time.

    When a hostname has several addresses you get several files, one per
    address, so you have somewhere to go when one server stops answering.

    Certificate checking is unaffected. OpenVPN validates the server against
    the CA and whatever `verify-x509-name` says, none of which involves the
    address you dialled, so pinning an IP does not weaken the tunnel.

.PARAMETER Path
    A .ovpn file, or a folder of them. Defaults to the `configs` folder beside
    this script, which is the intended way to use it: drop the configs you
    downloaded in there and run it.

.PARAMETER OutDir
    Where the pinned copies go. Defaults to a `pinned` folder beside the
    script. Originals are never modified unless you ask with -InPlace.

.PARAMETER Proxy
    HTTP proxy for the DoH lookups, e.g. http://127.0.0.1:10808. Omitted, the
    script tries DoH directly and falls back to a local proxy it finds itself.

.PARAMETER Resolver
    cloudflare (default) or google.

.PARAMETER MaxIps
    Cap on how many files to write per input. Default 4.

.PARAMETER InPlace
    Overwrite the input file with the first address instead of writing copies.

.PARAMETER NoTest
    Skip the reachability check on each pinned address.

.PARAMETER CheckCloudflare
    Do not pin anything - instead report whether Cloudflare is serving the
    connection you have right now.

    A VPN exit shared by enough people picks up a bad reputation, and
    Cloudflare then answers everything behind it with 403s and endless
    challenges. The tunnel is perfectly healthy; half the web just stops
    working. This tells the two apart.

    It has to run *while you are connected* to the server you want to judge.
    Nothing measurable from your own line says anything about how Cloudflare
    will treat an exit you are not using yet - so connect with one of the
    pinned configs, run this, and try the next file if it comes back dirty.

.PARAMETER Site
    Extra hosts for -CheckCloudflare to try, e.g. the site you actually care
    about. Cloudflare rules are per-customer, so a strict site can refuse an
    exit that Cloudflare's own pages serve happily.

.EXAMPLE
    .\Resolve-OvpnRemote.ps1
    .\Resolve-OvpnRemote.ps1 -Path .\configs -MaxIps 2
    .\Resolve-OvpnRemote.ps1 -Proxy http://127.0.0.1:10808
    .\Resolve-OvpnRemote.ps1 -CheckCloudflare
    .\Resolve-OvpnRemote.ps1 -CheckCloudflare -Site chatgpt.com
#>
[CmdletBinding()]
param(
    [string] $Path,
    [string] $OutDir,
    [string] $Proxy,

    [ValidateSet('cloudflare', 'google')]
    [string] $Resolver = 'cloudflare',

    [ValidateRange(1, 32)]
    [int] $MaxIps = 4,

    [switch] $InPlace,
    [switch] $NoTest,

    [switch] $CheckCloudflare,
    [string[]] $Site,

    [switch] $WhoIs,

    # Dot-sourced by Sweep-OvpnExits.ps1, which wants the probes and the
    # writers below and nothing else. Not for the command line.
    [switch] $AsLibrary
)

$ErrorActionPreference = 'Stop'

# .NET Framework still defaults to TLS 1.0 here, and every DoH endpoint
# refuses that, so the first lookup would fail for a reason that has nothing
# to do with censorship.
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$DohEndpoints = @{
    cloudflare = 'https://cloudflare-dns.com/dns-query'
    google     = 'https://dns.google/resolve'
}

# Ports worth trying for a local proxy, in rough order of popularity.
$ProxyPorts = 10808, 10809, 7890, 7891, 2080, 2081, 1080, 1081, 8889, 8080, 20171, 12334

# Everything a public hostname has no business resolving to. Rejecting the
# whole of RFC1918 and friends rather than a list of known-forged addresses
# means this keeps working when the censor picks a different one tomorrow.
$Reserved = @(
    @{ Net = '0.0.0.0';     Bits = 8  },
    @{ Net = '10.0.0.0';    Bits = 8  },
    @{ Net = '127.0.0.0';   Bits = 8  },
    @{ Net = '169.254.0.0'; Bits = 16 },
    @{ Net = '172.16.0.0';  Bits = 12 },
    @{ Net = '192.168.0.0'; Bits = 16 },
    @{ Net = '224.0.0.0';   Bits = 4  }
)


#--------------------------------------------------------------------- output

function Write-Head { param($t) Write-Host ''; Write-Host "  $t" -ForegroundColor Cyan; Write-Host "  $('-' * $t.Length)" -ForegroundColor DarkCyan }
function Write-Ok   { param($t) Write-Host '  [ ok ] ' -ForegroundColor Green  -NoNewline; Write-Host $t }
function Write-Bad  { param($t) Write-Host '  [fail] ' -ForegroundColor Red    -NoNewline; Write-Host $t }
function Write-Warn { param($t) Write-Host '  [warn] ' -ForegroundColor Yellow -NoNewline; Write-Host $t }
function Write-Info { param($t) Write-Host '         ' -NoNewline; Write-Host $t -ForegroundColor Gray }


#------------------------------------------------------------------ addresses

function ConvertTo-UInt32Ip {
    param([string] $Ip)
    $b = ([Net.IPAddress]::Parse($Ip)).GetAddressBytes()
    [Array]::Reverse($b)
    [BitConverter]::ToUInt32($b, 0)
}

function Test-ReservedIp {
    param([string] $Ip)
    $v = ConvertTo-UInt32Ip $Ip
    foreach ($r in $Reserved) {
        # Shifting a long left leaves the bits above 32 set, so mask back down
        # before narrowing - otherwise the cast throws on every prefix.
        $mask = if ($r.Bits -eq 0) { [uint32] 0 } else { [uint32](((0xFFFFFFFFL -shl (32 - $r.Bits)) -band 0xFFFFFFFFL)) }
        if (($v -band $mask) -eq ((ConvertTo-UInt32Ip $r.Net) -band $mask)) { return $true }
    }
    return $false
}

function Test-IsIpLiteral {
    param([string] $s)
    [bool]($s -match '^\d{1,3}(\.\d{1,3}){3}$')
}


#---------------------------------------------------------------------- proxy

function Test-HttpProxy {
    param([string] $ProxyHost, [int] $ProxyPort)
    $client = New-Object Net.Sockets.TcpClient
    try {
        # Local listener: it accepts at once or it is not there.
        if (-not $client.ConnectAsync($ProxyHost, $ProxyPort).Wait(2000)) { return $false }
        $s = $client.GetStream()
        $s.ReadTimeout = 20000; $s.WriteTimeout = 20000
        $req = "CONNECT cloudflare-dns.com:443 HTTP/1.1`r`nHost: cloudflare-dns.com:443`r`n`r`n"
        $b = [Text.Encoding]::ASCII.GetBytes($req)
        $s.Write($b, 0, $b.Length)
        $buf = New-Object byte[] 256
        $n = $s.Read($buf, 0, $buf.Length)
        if ($n -le 0) { return $false }
        return ([Text.Encoding]::ASCII.GetString($buf, 0, $n) -match '^HTTP/1\.[01] 200')
    }
    catch { return $false }
    finally { $client.Close() }
}

function Find-Proxy {
    foreach ($p in $ProxyPorts) {
        if (Test-HttpProxy '127.0.0.1' $p) { return "http://127.0.0.1:$p" }
    }
    return $null
}


#------------------------------------------------------------------------ DoH

function Resolve-Doh {
    param([string] $Name, [string] $ProxyUrl)

    $url = '{0}?name={1}&type=A' -f $DohEndpoints[$Resolver], [Uri]::EscapeDataString($Name)
    $req = [Net.HttpWebRequest]::Create($url)
    $req.Accept = 'application/dns-json'
    $req.UserAgent = 'ovpn-pin'
    $req.Timeout = 20000
    $req.ReadWriteTimeout = 20000
    # An explicit $null means "no proxy" to .NET; leaving it unset would let it
    # inherit the machine's system proxy, which is not what -Proxy '' asked for.
    $req.Proxy = if ($ProxyUrl) { New-Object Net.WebProxy($ProxyUrl) } else { $null }

    $resp = $req.GetResponse()
    try {
        $body = (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd()
    }
    finally { $resp.Close() }

    # Both providers answer with the same JSON shape. Reading the A records out
    # by regex rather than ConvertFrom-Json keeps this working on the older
    # PowerShell that ships with Windows, where the JSON depth handling differs.
    $ips = foreach ($m in [regex]::Matches($body, '"type"\s*:\s*1\s*,\s*"TTL"[^}]*?"data"\s*:\s*"([0-9.]+)"')) {
        $m.Groups[1].Value
    }
    if (-not $ips) {
        $ips = foreach ($m in [regex]::Matches($body, '"data"\s*:\s*"(\d{1,3}(?:\.\d{1,3}){3})"')) {
            $m.Groups[1].Value
        }
    }
    @($ips | Select-Object -Unique)
}

# Decide once whether DoH works without help. Trying direct for every hostname
# on a line where it is blocked costs 20 seconds each time for an answer that
# was never coming.
function Select-DohRoute {
    param([string] $Explicit)

    if ($Explicit) {
        Write-Info "using the proxy you named: $Explicit"
        return $Explicit
    }

    try {
        if (@(Resolve-Doh 'example.com' $null).Count -gt 0) {
            Write-Ok 'DoH works directly - no proxy needed'
            return $null
        }
    }
    catch { }

    Write-Info 'DoH did not answer directly; looking for a local proxy...'
    $p = Find-Proxy
    if (-not $p) {
        throw 'DoH is unreachable directly and no local HTTP proxy was found. Start your proxy (v2rayN, Clash, Nekoray, sing-box, Hiddify) and run this again, or name it with -Proxy http://127.0.0.1:PORT.'
    }
    Write-Ok "DoH will go through $p"
    return $p
}


#--------------------------------------------------------------------- config

# OpenVPN accepts `remote HOST [PORT] [PROTO]`, several of them as a failover
# list, and the same line inside <connection> blocks. Everything after the
# hostname is kept exactly as it was - the port and protocol are not ours to
# reinterpret.
function Get-RemoteLines {
    param([string[]] $Lines)
    $out = @()
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        if ($Lines[$i] -match '^(\s*remote\s+)(\S+)(\s*)(.*)$') {
            $out += [pscustomobject]@{
                Index  = $i
                Prefix = $Matches[1]
                Host   = $Matches[2]
                Gap    = if ($Matches[3]) { $Matches[3] } else { ' ' }
                Tail   = $Matches[4]
            }
        }
    }
    $out
}

function Get-RemotePort {
    param([string] $Tail, [string[]] $Lines)
    if ($Tail -match '^\s*(\d+)') { return [int]$Matches[1] }
    $p = $Lines | Where-Object { $_ -match '^\s*port\s+(\d+)' } | Select-Object -First 1
    if ($p -and $p -match '^\s*port\s+(\d+)') { return [int]$Matches[1] }
    return 1194
}

function Get-RemoteProto {
    param([string] $Tail, [string[]] $Lines)
    if ($Tail -match '\b(tcp-client|tcp4|tcp6|tcp|udp4|udp6|udp)\b') { return $Matches[1] }
    $p = $Lines | Where-Object { $_ -match '^\s*proto\s+(\S+)' } | Select-Object -First 1
    if ($p -and $p -match '^\s*proto\s+(\S+)') { return $Matches[1] }
    return 'udp'
}

function Test-TcpReachable {
    param([string] $Ip, [int] $Port)
    $c = New-Object Net.Sockets.TcpClient
    try { return $c.ConnectAsync($Ip, $Port).Wait(20000) }
    catch { return $false }
    finally { $c.Close() }
}


#--------------------------------------------------------------------- owners

# Who an address actually belongs to. A VPN provider owns almost none of its
# racks - it rents them from M247, Datacamp, Leaseweb, Cyberzone - and it is
# that landlord's name, not your provider's, that a site sees and blocks when
# it blocks "a VPN". So this is worth knowing for two reasons: exits sharing a
# landlord tend to be flagged together, and four addresses that turn out to be
# one operator in one city are not four alternatives.
#
# ip-api.com answers a hundred addresses in one request and names the operator
# outright - "M247", not a registry handle. Its free tier is HTTP only, so the
# query crosses the line in clear: a list of VPN addresses, to a third party.
# Nothing in it your DNS and SNI did not already say, but you should be the one
# deciding that, which is what -NoOwner is for. If the plain request does not
# come back at all, ipwho.is is asked over HTTPS instead, one address at a time.

# Select-Object -Unique compares every item against every item it has already
# seen, so a thousand addresses is half a million comparisons and the better
# part of a minute - on a list that was already in memory. A set does it once
# each. This is worth a function because the same list gets deduplicated in
# three places, and it was the slowest thing in the run.
function Get-UniqueStrings {
    param([string[]] $Values)
    $seen = New-Object 'System.Collections.Generic.HashSet[string]'
    $out = New-Object 'System.Collections.Generic.List[string]'
    foreach ($v in $Values) {
        if ($v -and $seen.Add($v)) { $out.Add($v) }
    }
    $out.ToArray()
}

# Write-Host is a trip through the host's UI layer every time it is called,
# and a thousand calls is thirty seconds of nothing else. Consecutive lines
# that share a tag go out together instead.
#
# In batches, though, rather than all at once: a coloured Write-Host of the
# whole thousand-line block throws OutOfMemoryException, on a string of a
# hundred and thirty kilobytes, in a 64-bit process with gigabytes free. The
# host is doing something quadratic with the buffer width it is handed. Two
# hundred lines a time is well under whatever that is, and still turns a
# thousand calls into six.
function Write-Rows {
    param([string[]] $Lines, [string] $Tag, [string] $Colour, [int] $Batch = 200)
    if (-not $Lines) { return }
    $prefix = "  [$Tag] "
    for ($i = 0; $i -lt $Lines.Count; $i += $Batch) {
        $chunk = $Lines[$i..([math]::Min($i + $Batch - 1, $Lines.Count - 1))]
        Write-Host ($prefix + ($chunk -join "`n$prefix")) -ForegroundColor $Colour
    }
}

function Get-StateDir {
    $d = Join-Path (Split-Path -Parent $PSCommandPath) '.state'
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d | Out-Null }
    $d
}

function Read-OwnerCache {
    $f = Join-Path (Get-StateDir) 'owners.tsv'
    $out = @{}
    if (-not (Test-Path $f)) { return $out }

    # A row here is about eighty characters and there is one per address. A
    # table in the megabytes is therefore not a table any more, and loading it
    # is how a lookup that should cost nothing takes the whole run down with
    # an OutOfMemoryException. Throw it away and start again; it is a cache,
    # and the next run rebuilds it.
    $size = (Get-Item $f).Length
    if ($size -gt 5MB) {
        Write-Warn ('{0} had grown to {1:n0} MB, which is not a table of addresses.' -f $f, ($size / 1MB))
        Write-Info 'Starting a fresh one - it will be looked up again from scratch.'
        Remove-Item $f -Force -ErrorAction SilentlyContinue
        return $out
    }

    # Read as UTF-8 and say so. Get-Content in Windows PowerShell defaults to
    # the ANSI code page, so a city with a letter outside it - Sao Paulo, say
    # - came back as the mojibake of its own UTF-8 bytes, got written out as
    # UTF-8 again, and doubled in length. Every run. Silently. This table
    # reached a gigabyte and a single row thirty-five megabytes that way,
    # which is what an OutOfMemoryException in the middle of a sweep turned
    # out to be.
    foreach ($line in [IO.File]::ReadAllLines($f, [Text.Encoding]::UTF8)) {
        # Belt and braces: no legitimate row is anywhere near this long, and
        # one that is should not be believed a second time.
        if ($line.Length -gt 400) { continue }
        $c = $line -split "`t"
        if ($c.Count -ge 5 -and $c[0]) {
            $when = [datetime]::MinValue
            if ($c.Count -ge 6 -and $c[5]) {
                try { $when = [datetime]::ParseExact($c[5], 'yyyy-MM-dd', $null) } catch { }
            }
            $out[$c[0]] = [pscustomobject]@{
                Ip = $c[0]; Asn = $c[1]; Owner = $c[2]
                Country = $c[3]; City = $c[4]; Checked = $when
            }
        }
    }
    $out
}

# Read once per run, not once per question. The sweep asks about an exit after
# every config it connects, and re-reading and re-parsing a table of a
# thousand rows to answer each of them - then writing the whole thing back -
# is work nobody asked for.
$script:OwnerCache = $null
$script:OwnerDirty = $false

function Get-OwnerCache {
    if ($null -eq $script:OwnerCache) { $script:OwnerCache = Read-OwnerCache }
    $script:OwnerCache
}

function Save-OwnerCache {
    if (-not $script:OwnerDirty) { return }
    $f = Join-Path (Get-StateDir) 'owners.tsv'
    $today = Get-Date -Format 'yyyy-MM-dd'
    $rows = foreach ($k in ($script:OwnerCache.Keys | Sort-Object)) {
        $o = $script:OwnerCache[$k]
        $when = if ($o.Checked -and $o.Checked -ne [datetime]::MinValue) {
            $o.Checked.ToString('yyyy-MM-dd')
        } else { $today }
        ($o.Ip, $o.Asn, $o.Owner, $o.Country, $o.City, $when) -join "`t"
    }
    [IO.File]::WriteAllText($f, ($rows -join "`n") + "`n", (New-Object Text.UTF8Encoding($false)))
    $script:OwnerDirty = $false
}

function Invoke-OwnerBatch {
    param([string[]] $Ips, [string] $ProxyUrl)

    $url = 'http://ip-api.com/batch?fields=status,query,country,city,isp,org,as,asname,hosting'
    $req = [Net.HttpWebRequest]::Create($url)
    $req.Method = 'POST'
    $req.ContentType = 'application/json'
    $req.UserAgent = 'ovpn-pin'
    $req.Timeout = 20000
    $req.ReadWriteTimeout = 20000
    $req.Proxy = if ($ProxyUrl) { New-Object Net.WebProxy($ProxyUrl) } else { $null }
    # Same reason as the Cloudflare probe: this gets asked from inside a
    # tunnel that has just come up.
    $req.KeepAlive = $false
    # .NET puts `Expect: 100-continue` on every POST and waits to be invited
    # before sending the body. This server answers that with 200 and an empty
    # body, so the request appears to succeed and returns nothing - which is a
    # far more annoying failure than an error would have been.
    $req.ServicePoint.Expect100Continue = $false

    $payload = '["' + ($Ips -join '","') + '"]'
    $bytes = [Text.Encoding]::UTF8.GetBytes($payload)
    $req.ContentLength = $bytes.Length
    $s = $req.GetRequestStream()
    try { $s.Write($bytes, 0, $bytes.Length) } finally { $s.Close() }

    $resp = $req.GetResponse()
    try { (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd() }
    finally { $resp.Close() }
}

# Same regex-rather-than-ConvertFrom-Json reasoning as the DoH parser above.
# Only flat fields are asked for, so an object is everything between one pair
# of braces and nothing nests.
function ConvertFrom-OwnerJson {
    param([string] $Body)
    foreach ($m in [regex]::Matches($Body, '\{[^{}]*\}')) {
        $o = $m.Value
        $get = {
            param($k)
            if ($o -match ('"' + $k + '"\s*:\s*"([^"]*)"')) { $Matches[1] } else { '' }
        }
        $ip = & $get 'query'
        if (-not $ip) { continue }
        $as = & $get 'as'
        $asn = if ($as -match '^(AS\d+)') { $Matches[1] } else { '' }
        $owner = & $get 'asname'
        if (-not $owner) { $owner = & $get 'isp' }
        if (-not $owner) { $owner = & $get 'org' }
        [pscustomobject]@{
            Ip      = $ip
            Asn     = $asn
            Owner   = $owner
            Country = & $get 'country'
            City    = & $get 'city'
        }
    }
}

function Invoke-OwnerHttps {
    param([string] $Ip, [string] $ProxyUrl)
    $req = [Net.HttpWebRequest]::Create("https://ipwho.is/$Ip")
    $req.UserAgent = 'ovpn-pin'
    $req.Timeout = 15000
    $req.ReadWriteTimeout = 15000
    $req.KeepAlive = $false
    $req.Proxy = if ($ProxyUrl) { New-Object Net.WebProxy($ProxyUrl) } else { $null }
    $resp = $req.GetResponse()
    $body = try { (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd() } finally { $resp.Close() }

    $f = { param($k) if ($body -match ('"' + $k + '"\s*:\s*"([^"]*)"')) { $Matches[1] } else { '' } }
    $asn = if ($body -match '"asn"\s*:\s*(\d+)') { 'AS' + $Matches[1] } else { '' }
    # org and isp live inside the "connection" object; the outer body has the
    # location fields. One flat regex over the whole thing gets both. isp is
    # the tidier of the two here - "M247 Europe SRL" against org's
    # "M247 LTD Frankfurt Infrastructure".
    $owner = & $f 'isp'
    if (-not $owner) { $owner = & $f 'org' }
    [pscustomobject]@{
        Ip = $Ip; Asn = $asn; Owner = $owner
        Country = (& $f 'country'); City = (& $f 'city')
    }
}

# ip -> { Owner, Asn, Country, City, Hosting }, for every address it could
# find out about. Anything already known is answered from .state\owners.tsv,
# so a second run costs nothing and works with the line down.
function Get-IpOwner {
    param([string[]] $Ips, [string] $ProxyUrl)

    $cache = Get-OwnerCache
    $asked = Get-UniqueStrings $Ips

    # An address that could not be looked up was being left out of the table
    # entirely, so every later run asked about it again - and a handful of
    # addresses no service will name turn into the same wasted minute, every
    # time, for ever. They get a row of their own instead, and are only asked
    # about again a week later in case the service was simply having a bad day.
    $stale = (Get-Date).AddDays(-7)
    $want = @($asked | Where-Object {
        $o = $cache[$_]
        (-not $o) -or ($o.Owner -eq '-' -and $o.Checked -lt $stale)
    })

    if (-not $want) { return (Select-KnownOwners $cache $asked) }

    $learned = 0
    $proxy = $ProxyUrl

    # Say what is happening while it happens. A hundred addresses go in one
    # request, and there is a deliberate five second wait between requests -
    # so a few hundred new ones is half a minute in which the screen would
    # otherwise show nothing at all, which reads as broken rather than busy.
    # Not for one or two, which is what the sweep asks about after each config.
    $batches = [math]::Ceiling($want.Count / 100)
    $chatty  = $want.Count -gt 10
    if ($chatty) {
        Write-Info "asking about $($want.Count) new address(es), $batches request(s)"
    }

    for ($i = 0; $i -lt $want.Count; $i += 100) {
        $chunk = @($want[$i..([math]::Min($i + 99, $want.Count - 1))])
        if ($chatty) {
            Write-Info ('  request {0} of {1} - {2} addresses' -f ([int]($i / 100) + 1), $batches, $chunk.Count)
        }
        $body = $null
        try { $body = Invoke-OwnerBatch $chunk $proxy }
        catch {
            # Plain HTTP is the first thing a filtering middlebox eats. Try it
            # through a local proxy once before giving up on the fast path.
            if (-not $proxy) {
                $proxy = Find-Proxy
                if ($proxy) { try { $body = Invoke-OwnerBatch $chunk $proxy } catch { } }
            }
        }
        if ($body) {
            foreach ($o in (ConvertFrom-OwnerJson $body)) {
                if ($o.Owner) { $cache[$o.Ip] = (Add-Checked $o); $learned++ }
            }
        }
        else {
            foreach ($ip in $chunk) {
                try {
                    $o = Invoke-OwnerHttps $ip $proxy
                    if ($o.Owner) { $cache[$ip] = (Add-Checked $o); $learned++ }
                }
                catch { }
            }
        }

        # Whatever is still missing after both services had their turn gets
        # written down as missing, so the next run does not start from here.
        foreach ($ip in $chunk) {
            if (-not $cache[$ip] -or $cache[$ip].Owner -eq '-') {
                $cache[$ip] = [pscustomobject]@{
                    Ip = $ip; Asn = ''; Owner = '-'; Country = ''; City = ''
                    Checked = (Get-Date)
                }
            }
        }
        $script:OwnerDirty = $true

        # 15 batch requests a minute on the free tier, and being throttled
        # returns nothing rather than waiting.
        if ($i + 100 -lt $want.Count) {
            if ($chatty) { Write-Info '  waiting 5s - the free tier allows 15 requests a minute' }
            Start-Sleep -Seconds 5
        }
    }

    if ($chatty) { Write-Info "$learned of $($want.Count) came back with a name" }
    Save-OwnerCache
    Select-KnownOwners $cache $asked
}

function Add-Checked {
    param($O)
    [pscustomobject]@{
        Ip = $O.Ip; Asn = $O.Asn; Owner = $O.Owner
        Country = $O.Country; City = $O.City; Checked = (Get-Date)
    }
}

# Only the addresses that were asked about, and only the ones with a real
# answer - a row saying "nobody would tell us" is for the cache's benefit, not
# the caller's.
function Select-KnownOwners {
    param([hashtable] $Cache, [string[]] $Ips)
    $out = @{}
    foreach ($ip in $Ips) {
        $o = $Cache[$ip]
        if ($o -and $o.Owner -and $o.Owner -ne '-') { $out[$ip] = $o }
    }
    $out
}

function Format-Owner {
    param($O)
    if (-not $O -or -not $O.Owner -or $O.Owner -eq '-') { return '?' }
    $s = $O.Owner
    if ($O.Asn) { $s = "$s $($O.Asn)" }
    $s
}

function Show-Owners {
    param([string] $PinnedDir, [string] $ProxyUrl)

    Write-Head 'Who these addresses belong to'

    if (-not (Test-Path $PinnedDir)) {
        Write-Info "There is no $PinnedDir yet - run this without -WhoIs first and it"
        Write-Info 'writes the pinned configs whose addresses this reads.'
        Write-Host ''
        return
    }

    $files = @(Get-ChildItem $PinnedDir -Filter *.ovpn -File | Sort-Object Name)
    $rows = foreach ($f in $files) {
        # The remote line is in the first few lines of the file, and the rest
        # of it is a certificate. No reason to read that a thousand times.
        $lines = Get-Content -LiteralPath $f.FullName -TotalCount 40
        $r = Get-RemoteLines $lines | Select-Object -First 1
        if ($r -and (Test-IsIpLiteral $r.Host)) {
            [pscustomobject]@{ File = $f.Name; Ip = $r.Host }
        }
    }
    $rows = @($rows)
    if (-not $rows) {
        Write-Info "Nothing in $PinnedDir has an address in it."
        Write-Host ''
        return
    }

    $uniq  = Get-UniqueStrings $rows.Ip
    $cache = Get-OwnerCache
    $known = @($uniq | Where-Object { $cache.ContainsKey($_) }).Count
    $new   = $uniq.Count - $known

    Write-Info "$($rows.Count) files, $($uniq.Count) addresses"
    if ($known) { Write-Info "$known of them answered from $(Join-Path (Get-StateDir) 'owners.tsv') - nothing to ask" }
    if ($new)   { Write-Info "$new have not been looked up before" }

    $owners = Get-IpOwner $uniq $ProxyUrl

    Write-Host ''
    # Tally as we go rather than grouping a thousand objects afterwards, and
    # write the lines out in runs rather than one call at a time.
    $tally   = @{}
    $pending = New-Object 'System.Collections.Generic.List[string]'
    $pendTag = $null
    $missing = 0

    foreach ($row in $rows) {
        $o = $owners[$row.Ip]
        $where = if ($o) { (@($o.City, $o.Country) | Where-Object { $_ }) -join ', ' } else { '' }
        $line = '{0,-52} {1,-16} {2,-28} {3}' -f $row.File, $row.Ip, (Format-Owner $o), $where

        if ($o) {
            $name = Format-Owner $o
            if (-not $tally.ContainsKey($name)) {
                $tally[$name] = [pscustomobject]@{ Count = 0; Countries = (New-Object 'System.Collections.Generic.HashSet[string]') }
            }
            $tally[$name].Count++
            if ($o.Country) { [void] $tally[$name].Countries.Add($o.Country) }
            $tag = ' ok '
        }
        else { $missing++; $tag = 'warn' }

        if ($tag -ne $pendTag) {
            if ($pendTag) { Write-Rows $pending.ToArray() $pendTag $(if ($pendTag -eq ' ok ') { 'Gray' } else { 'Yellow' }) }
            $pending.Clear()
            $pendTag = $tag
        }
        $pending.Add($line)
    }
    if ($pendTag) { Write-Rows $pending.ToArray() $pendTag $(if ($pendTag -eq ' ok ') { 'Gray' } else { 'Yellow' }) }

    # The point of the whole exercise: four files that share a landlord are
    # not four independent things to try when one of them gets blocked.
    if ($tally.Count) {
        Write-Head 'By landlord'
        foreach ($name in ($tally.Keys | Sort-Object { $tally[$_].Count } -Descending)) {
            $t = $tally[$name]
            $where = if ($t.Countries.Count -le 4) { ($t.Countries | Sort-Object) -join ', ' }
                     else { "$($t.Countries.Count) countries" }
            $word  = if ($t.Count -eq 1) { 'address  ' } else { 'addresses' }
            Write-Info ('{0,-30} {1,3} {2}   {3}' -f $name, $t.Count, $word, $where)
        }
    }
    Write-Host ''
    if ($missing) { Write-Warn "$missing address(es) could not be looked up - the lookup service did not answer." }
    Write-Info 'Landlords matter because a site blocking "a VPN" is usually blocking'
    Write-Info 'the hosting company, not your provider - so exits sharing one tend to'
    Write-Info 'be flagged together, and are not really alternatives to each other.'
    Write-Info "Remembered in $(Join-Path (Get-StateDir) 'owners.tsv')."
    Write-Host ''
}


#----------------------------------------------------------------- cloudflare

# Deliberately not through any proxy: the whole question is what the current
# default route - the tunnel - gets served, and a proxy would answer for
# itself instead. A browser user agent matters too; a bare or scripted one
# gets challenged on its own merits and would frame a clean exit as dirty.
# Which interface the machine would really use for a public address, and
# whether it is a tunnel.
#
# Two traps, both of which have already caught this repo once. The first is
# reading the 0.0.0.0/0 route: redirect-gateway def1 does not replace it, it
# lays 0.0.0.0/1 and 128.0.0.0/1 over the top and those win on longest prefix,
# so the default route names the wrong thing while a tunnel is up. The second
# is asking Get-NetAdapter: an IKEv2 or L2TP client - Windscribe's, among
# others - is a RAS interface rather than a network adapter, so it is absent
# from Get-NetAdapter even with -IncludeHidden, and a check written that way
# reports no tunnel while every packet is going through one. The route itself
# knows the alias, so that is what gets asked.
function Get-DefaultHop {
    $alias = ''; $desc = ''; $index = 0
    try {
        $hop = Find-NetRoute -RemoteIPAddress 1.1.1.1 -ErrorAction Stop | Select-Object -First 1
        if ($hop) {
            $alias = $hop.InterfaceAlias
            $index = [int] $hop.InterfaceIndex
            $a = Get-NetAdapter -InterfaceIndex $hop.InterfaceIndex -ErrorAction SilentlyContinue
            if ($a) {
                $desc = $a.InterfaceDescription
                if (-not $alias) { $alias = $a.Name }
            }
        }
    }
    catch { }
    if (-not $alias) { return $null }

    [pscustomobject]@{
        Alias       = $alias
        Description = $desc
        Index       = $index
        IsTunnel    = ("$alias $desc" -match
            'TAP-Windows|WinTun|Wintun|OpenVPN|WireGuard|IKEv2|L2TP|PPTP|SSTP|WAN Miniport|' +
            'Windscribe|NordLynx|NordVPN|ProtonVPN|Mullvad|ExpressVPN|Surfshark|Cloudflare WARP|VPN')
    }
}

function Invoke-CfProbe {
    param([string] $Url, [int] $TimeoutMs = 20000)

    $req = [Net.HttpWebRequest]::Create($Url)
    $req.Proxy = $null
    $req.Timeout = $TimeoutMs
    $req.ReadWriteTimeout = $TimeoutMs
    # No connection reuse. .NET pools keep-alive connections per host, and a
    # socket opened before a tunnel came up is still bound to the address it
    # was opened from - so the reply comes back naming the old exit, and the
    # tunnel gets written off as not carrying traffic when it is carrying it
    # perfectly well. Every measurement here has to be its own connection.
    $req.KeepAlive = $false
    # And nothing out of a cache either, for the same reason: this is a
    # measurement of right now, not a request for a document.
    $req.CachePolicy = New-Object Net.Cache.RequestCachePolicy([Net.Cache.RequestCacheLevel]::NoCacheNoStore)
    $req.UserAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'
    $req.Accept = 'text/html,application/xhtml+xml,*/*'
    $req.AllowAutoRedirect = $true

    $status = $null; $body = ''; $headers = $null; $err = $null
    try {
        $resp = $req.GetResponse()
        try {
            $status = [int] $resp.StatusCode
            $headers = $resp.Headers
            $body = (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd()
        }
        finally { $resp.Close() }
    }
    catch [Net.WebException] {
        # A 403 is a response, not a failure, and it is the interesting case -
        # so read it rather than letting it surface as an exception.
        if ($_.Exception.Response) {
            $r = $_.Exception.Response
            try {
                $status = [int] $r.StatusCode
                $headers = $r.Headers
                $body = (New-Object IO.StreamReader($r.GetResponseStream())).ReadToEnd()
            }
            catch { }
            finally { $r.Close() }
        }
        else { $err = $_.Exception.Message }
    }
    catch { $err = $_.Exception.Message }

    $mitigated = if ($headers) { $headers['cf-mitigated'] } else { $null }
    $ray = if ($headers) { $headers['cf-ray'] } else { $null }

    # Cloudflare's block and challenge pages carry their own error numbers.
    # 1020 is a WAF rule, 1015 rate limiting, and the interstitials say so in
    # the title - all of which mean the address you arrived from, not the site.
    $challenged = ($mitigated -eq 'challenge') -or
                  ($body -match 'Just a moment|Attention Required|Checking your browser|cf-challenge|__cf_chl') -or
                  ($body -match 'Error 10(20|15|09)')

    $verdict =
        if ($err)                       { 'unreachable' }
        elseif ($challenged)            { 'challenged' }
        elseif ($status -eq 403)        { 'blocked' }
        elseif ($status -ge 200 -and $status -lt 400) { 'ok' }
        else                            { "http $status" }

    [pscustomobject]@{
        Url     = $Url
        Status  = $status
        Verdict = $verdict
        Ray     = $ray
        Body    = $body
        Error   = $err
    }
}

function Get-CfTrace {
    param([string] $HostName = 'www.cloudflare.com')
    $r = Invoke-CfProbe "https://$HostName/cdn-cgi/trace"
    $kv = @{}
    foreach ($line in ($r.Body -split '\r?\n')) {
        if ($line -match '^([a-z_]+)=(.*)$') { $kv[$Matches[1]] = $Matches[2] }
    }
    [pscustomobject]@{ Probe = $r; Fields = $kv }
}

function Show-CloudflareCheck {
    param([string] $PinnedDir, [string[]] $Extra)

    Write-Head 'Where this is measuring from'

    $hop = Get-DefaultHop
    if ($hop) {
        $where = $hop.Alias + $(if ($hop.Description) { " [$($hop.Description)]" })
        if ($hop.IsTunnel) { Write-Ok "traffic leaves over $where" }
        else {
            Write-Info "traffic leaves over $where"
            Write-Info 'That does not name a tunnel - but some clients route without one,'
            Write-Info 'so the address Cloudflare reports below is the real answer.'
        }
    }

    $trace = Get-CfTrace
    if ($trace.Probe.Error) {
        Write-Host ''
        Write-Bad "Cloudflare did not answer at all: $($trace.Probe.Error)"
        Write-Info 'That is a dead path, not a reputation problem.'
        Write-Host ''
        return
    }

    $egress = $trace.Fields['ip']
    $colo   = $trace.Fields['colo']
    $loc    = $trace.Fields['loc']
    Write-Info "Cloudflare sees you as $egress$(if ($loc) { " in $loc" })$(if ($colo) { ", via $colo" })"

    # If the exit matches an address we pinned, say which file produced it.
    # Plenty of providers NAT the exit to a different address than the one you
    # dialled, so a mismatch is normal rather than a warning sign.
    if ($egress -and (Test-Path $PinnedDir)) {
        $match = Get-ChildItem $PinnedDir -Filter *.ovpn -File -ErrorAction SilentlyContinue |
            Where-Object { (Get-Content $_.FullName) -match ('^\s*remote\s+' + [regex]::Escape($egress) + '\b') } |
            Select-Object -First 1
        if ($match) { Write-Info "that is the exit of $($match.Name)" }
        else { Write-Info 'no pinned config dials that address - normal, most exits are NATed' }
    }

    Write-Head 'What Cloudflare serves this exit'

    $targets = @(
        [pscustomobject]@{ Name = 'www.cloudflare.com';   Url = 'https://www.cloudflare.com/cdn-cgi/trace' }
        [pscustomobject]@{ Name = 'speed.cloudflare.com'; Url = 'https://speed.cloudflare.com/__down?bytes=1000' }
    )
    # Split on commas ourselves. Run through powershell -File, "-Site a,b"
    # arrives as the single string "a,b" rather than two elements, and that
    # then builds a URL with a comma in the hostname and throws.
    foreach ($s in (($Extra -join ',') -split '[,\s]+' | Where-Object { $_ })) {
        $h = $s -replace '^https?://', '' -replace '/.*$', ''
        if ($h -notmatch '^[A-Za-z0-9._-]+$') {
            Write-Warn "skipping '$s' - not a hostname"
            continue
        }
        $targets += [pscustomobject]@{ Name = $h; Url = "https://$h/" }
    }

    $results = foreach ($t in $targets) {
        $p = if ($t.Url -eq 'https://www.cloudflare.com/cdn-cgi/trace') { $trace.Probe }
             else {
                 # One unreachable site must not end the run - it is a result,
                 # and the sites after it still have something to say.
                 try { Invoke-CfProbe $t.Url }
                 catch { [pscustomobject]@{ Url = $t.Url; Status = $null; Verdict = 'unreachable'; Ray = $null; Body = ''; Error = $_.Exception.Message } }
             }
        switch ($p.Verdict) {
            'ok'          { Write-Ok   ("{0,-24} {1}" -f $t.Name, 'served') }
            'challenged'  { Write-Bad  ("{0,-24} {1}" -f $t.Name, 'challenge page - this exit is flagged') }
            'blocked'     { Write-Bad  ("{0,-24} {1}" -f $t.Name, '403 refused') }
            'unreachable' { Write-Warn ("{0,-24} {1}" -f $t.Name, "no answer - $($p.Error)") }
            default       { Write-Warn ("{0,-24} {1}" -f $t.Name, $p.Verdict) }
        }
        $p
    }

    $bad  = @($results | Where-Object { $_.Verdict -in 'challenged', 'blocked' }).Count
    $good = @($results | Where-Object { $_.Verdict -eq 'ok' }).Count

    Write-Host ''
    if ($bad -eq 0 -and $good -gt 0) {
        Write-Ok 'this exit is clean as far as Cloudflare is concerned.'
    }
    elseif ($good -eq 0) {
        Write-Bad 'Cloudflare refuses everything from this exit.'
        Write-Info 'The address has a bad reputation - too many people behind it, or it'
        Write-Info 'is a known VPN range. Nothing is wrong with the tunnel, and no'
        Write-Info 'setting on this machine changes it.'
        Write-Info 'Connect with a different pinned config and run this again.'
    }
    else {
        Write-Warn 'partly served: some Cloudflare sites answer and others refuse.'
        Write-Info 'That is the usual shape of a mildly flagged exit - the rules are'
        Write-Info 'per-customer, so a strict site refuses what Cloudflare itself'
        Write-Info 'serves. Usable, but expect captchas on the strict ones.'
    }
    Write-Host ''
    Write-Info 'This judges the exit you are on right now and nothing else. To compare'
    Write-Info 'servers, connect with another pinned config and run it again.'
    Write-Host ''
}


#----------------------------------------------------------------------- main

# Sourced rather than run: the caller wanted the functions above, and pinning
# anything now would be a surprise.
if ($AsLibrary) { return }

try {
    Write-Host ''
    Write-Host '  Resolve-OvpnRemote' -ForegroundColor White
    Write-Host '  pins the remote line of an OpenVPN config to a real IP, over DoH' -ForegroundColor DarkGray

    $root = Split-Path -Parent $PSCommandPath
    if (-not $Path)   { $Path   = Join-Path $root 'configs' }
    if (-not $OutDir) { $OutDir = Join-Path $root 'pinned' }

    if ($CheckCloudflare) {
        Show-CloudflareCheck $OutDir $Site
        exit 0
    }

    if ($WhoIs) {
        Show-Owners $OutDir $Proxy
        exit 0
    }

    # Make the inbox rather than complain about it: a first run with nothing in
    # it should leave you with somewhere obvious to put the files.
    $isDefaultInbox = $Path -eq (Join-Path $root 'configs')
    if (-not (Test-Path $Path)) {
        if (-not $isDefaultInbox) { throw "No such path: $Path" }
        New-Item -ItemType Directory -Path $Path | Out-Null
    }

    $files = if ((Get-Item $Path).PSIsContainer) {
        @(Get-ChildItem $Path -Filter *.ovpn -File | Sort-Object Name)
    }
    else { @(Get-Item $Path) }

    # An empty inbox on a first run is not an error, it is the setup step.
    if (-not $files) {
        Write-Head 'Nothing to do yet'
        Write-Info "Put the .ovpn files you downloaded from your provider into:"
        Write-Info "     $Path"
        Write-Info 'then run this again.'
        Write-Host ''
        exit 0
    }

    if (-not $InPlace -and -not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }

    Write-Head 'Resolver'
    $route = Select-DohRoute $Proxy
    Write-Info "provider: $Resolver ($($DohEndpoints[$Resolver]))"

    Write-Head "Configs ($($files.Count))"

    $utf8 = New-Object Text.UTF8Encoding($false)
    $cache = @{}
    $written = 0
    $skipped = 0

    foreach ($f in $files) {
        # Keep whatever line ending the file arrived with. Rewriting a config
        # from LF to CRLF changes every line of it, which makes the one edit
        # that matters impossible to see in a diff.
        $text = [IO.File]::ReadAllText($f.FullName)
        $nl = if ($text -match "`r`n") { "`r`n" } else { "`n" }
        $lines = $text -split '\r?\n'
        if ($lines.Count -and $lines[-1] -eq '') { $lines = $lines[0..($lines.Count - 2)] }
        $remotes = @(Get-RemoteLines $lines)

        if (-not $remotes) {
            Write-Warn "$($f.Name): no remote line, skipped"
            $skipped++
            continue
        }

        # Resolve each distinct hostname once across the whole run. A folder of
        # configs for one provider repeats the same names constantly.
        $resolved = @{}
        foreach ($r in $remotes) {
            if (Test-IsIpLiteral $r.Host) {
                $resolved[$r.Host] = @($r.Host)
                continue
            }
            if ($cache.ContainsKey($r.Host)) {
                $resolved[$r.Host] = $cache[$r.Host]
                continue
            }
            try { $ips = @(Resolve-Doh $r.Host $route) }
            catch { $ips = @() }

            $clean = @($ips | Where-Object { -not (Test-ReservedIp $_) })
            $forged = @($ips | Where-Object { Test-ReservedIp $_ })
            if ($forged) {
                Write-Warn "$($r.Host): threw away $($forged -join ', ') - not a public address"
            }
            $cache[$r.Host] = $clean
            $resolved[$r.Host] = $clean
        }

        $empty = @($remotes | Where-Object { -not $resolved[$_.Host] })
        if ($empty) {
            Write-Bad "$($f.Name): could not resolve $(($empty.Host | Select-Object -Unique) -join ', ')"
            $skipped++
            continue
        }

        # How many variants to write: the most addresses any one hostname in
        # this file has. A file whose hosts resolve to different counts reuses
        # the last address of the shorter ones rather than dropping a remote.
        $count = ($remotes | ForEach-Object { $resolved[$_.Host].Count } | Measure-Object -Maximum).Maximum
        $count = [Math]::Min($count, $MaxIps)
        if ($InPlace) { $count = 1 }

        for ($v = 0; $v -lt $count; $v++) {
            $copy = [string[]]::new($lines.Length)
            [Array]::Copy($lines, $copy, $lines.Length)

            $picked = @()
            foreach ($r in $remotes) {
                $ips = $resolved[$r.Host]
                $ip = $ips[[Math]::Min($v, $ips.Count - 1)]
                $copy[$r.Index] = '{0}{1}{2}{3}' -f $r.Prefix, $ip, $r.Gap, $r.Tail
                $picked += [pscustomobject]@{
                    Host  = $r.Host
                    Ip    = $ip
                    Port  = (Get-RemotePort $r.Tail $lines)
                    Proto = (Get-RemoteProto $r.Tail $lines)
                }
            }

            $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm'
            $header = @("# pinned by Resolve-OvpnRemote on $stamp - $Resolver over DoH")
            foreach ($p in $picked) {
                if ($p.Host -ne $p.Ip) { $header += "#   $($p.Host) -> $($p.Ip)" }
            }
            $body = $header + $copy

            $first = $picked[0]
            $target = if ($InPlace) {
                $f.FullName
            }
            else {
                Join-Path $OutDir ('{0}_{1}.ovpn' -f [IO.Path]::GetFileNameWithoutExtension($f.Name), $first.Ip)
            }
            [IO.File]::WriteAllText($target, (($body -join $nl) + $nl), $utf8)
            $written++

            $note = ''
            if (-not $NoTest) {
                if ($first.Proto -like 'udp*') {
                    # A UDP port cannot be probed: OpenVPN drops any datagram
                    # without a valid tls-auth HMAC, so silence means "blocked"
                    # and "working" equally. Saying nothing beats guessing.
                    $note = '  (udp - not testable)'
                }
                elseif (Test-TcpReachable $first.Ip $first.Port) { $note = '  reachable' }
                else { $note = '  NOT reachable' }
            }

            $name = Split-Path $target -Leaf
            if ($note -eq '  NOT reachable') {
                Write-Warn ("{0}  {1}:{2}{3}" -f $name, $first.Ip, $first.Port, $note)
            }
            else {
                Write-Ok ("{0}  {1}:{2}{3}" -f $name, $first.Ip, $first.Port, $note)
            }
        }
    }

    Write-Head 'Done'
    Write-Ok "$written file(s) written$(if ($skipped) { ", $skipped skipped" })"
    if (-not $InPlace) {
        Write-Info "in: $OutDir"
        Write-Info 'The originals were not touched.'
    }
    Write-Host ''
    Write-Info 'Import one of these into your OpenVPN client and connect. Nothing in'
    Write-Info 'it depends on your resolver any more, so a poisoned answer cannot'
    Write-Info 'send it to the wrong address.'
    Write-Host ''
    Write-Info 'Where a hostname gave several addresses you have a file for each.'
    Write-Info 'They are alternatives, not a ranking - if one stops answering, try'
    Write-Info 'the next. Re-run this when they all go stale: providers move'
    Write-Info 'addresses, and a pinned file cannot follow them.'
    Write-Host ''
    Write-Info 'Once connected, check whether that exit is one Cloudflare will serve:'
    Write-Info "     .\$(Split-Path $PSCommandPath -Leaf) -CheckCloudflare"
    Write-Host ''
}
catch {
    Write-Host ''
    Write-Bad $_.Exception.Message
    Write-Host ''
    exit 1
}
