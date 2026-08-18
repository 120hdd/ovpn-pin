<#
.SYNOPSIS
    Connects each pinned config in turn, asks Cloudflare what it makes of that
    exit, drops it, and moves on. The Windows half of `ovpn-connect.sh --sweep`.

.DESCRIPTION
    Reachability - which the pinner already reports - only says a server
    answers on its port. It says nothing about what the web does with the
    address you come out of, and that is the part that ruins an afternoon: the
    tunnel is perfectly healthy and half the sites you want serve you a
    challenge page instead.

    Nothing observable from your own line predicts that. The only way to know
    how an exit is treated is to be on it, so this connects to each pinned
    config for real, measures, and disconnects. Budget about a minute each.

    It needs the OpenVPN *community* client - openvpn.exe, the command line
    one. OpenVPN Connect and the GUI cannot be driven from a script, which is
    the whole reason this file exists:

        winget install --id OpenVPNTechnologies.OpenVPN

    If you would rather not install it, -Wsl runs the Linux script in WSL
    instead. Same measurement, see the notes on that parameter.

    Verdicts, and what to do about each:

        clean    Cloudflare serves everything from this exit. Use it.
        partly   some sites answer and others refuse. Usable, expect captchas.
        dirty    everything refused. The address has a bad reputation. Nothing
                 on this machine changes that - use another exit.

    Every verdict is written to .state\exits.tsv, the same table the Linux
    script keeps, so the two halves of this repo can read each other's results.

.PARAMETER Name
    Only sweep configs whose filename contains this. `de-fra`, `us-`, and so
    on. Without it, everything in pinned\.

.PARAMETER Landlord
    Only sweep configs whose address is rented from one of these. Names or AS
    numbers, comma separated: `-Landlord M247,CDN77` or `-Landlord AS9009`.
    Matched loosely, so `M247` finds "M247 AS9009".

    This is the filter that saves the most time, because a provider's address
    list is not the spread of independent servers it looks like. Sweeping four
    addresses from one hosting company is close to sweeping one: a site that
    refuses the first usually refuses the rest, since it is the company being
    refused rather than any of the addresses.

.PARAMETER PickLandlord
    List the hosting companies behind the configs about to be swept, numbered,
    and sweep only the ones you choose. Answer with numbers - `1,3` or `1-4`
    or `2,5-7` - or enter for all of them.

.PARAMETER OnePer
    One file per location instead of all of them. A hostname usually resolves
    to four addresses and you got four files; this takes the first of each,
    which turns a three-hour sweep of every address into a forty-minute sweep
    of every location. Start here, then sweep one location's addresses in full
    with -Name once you know which location you want.

.PARAMETER First
    Stop after this many configs. Handy for seeing what a sweep looks like
    before committing an afternoon to one.

.PARAMETER SuccessDir
    Where to copy the configs that actually connected, with the handshake time
    written on the front of each name. Defaults to a `success` folder beside
    this script.

        04.2s-de-fra.prod.surfshark.com_tcp_146.70.160.213.ovpn
        11.8s-jp-tok.prod.surfshark.com_tcp_146.70.211.107.ovpn

    Sorted by name, that is a list of what works, quickest first - which is
    the thing you want at the moment the tunnel drops and you need another one
    in a hurry. The seconds are zero-padded because otherwise 10s sorts before
    4s in every file manager there is.

    The originals in pinned\ are copied, never moved. A config swept again
    replaces its own old entry rather than accumulating one per run.

    Inside it, a `landlord` folder gets a second copy of everything whose exit
    could be traced to a hosting company, labelled with the company and the
    country the exit actually came out in:

        04.2s-M247-AS9009-DE-de-fra.prod.surfshark.com_tcp_146.70.160.213.ovpn
        02.7s-Cyberzonehub-AS209854-CY-ad-leu.prod...._62.197.152.115.ovpn

    Same files, sorted differently on purpose. When a whole hosting company
    turns out to be blocked - which is the usual shape of it, since that is
    what gets blocked - this is the folder that says what else you have and
    where it comes out. The country is Cloudflare's reading of the exit, not
    the provider's label on the file: `de-fra` is where they say it is, `DE`
    is where it answered from, and the two do not always agree.

.PARAMETER Site
    Extra hosts to test on each exit, comma separated. Cloudflare's rules are
    per-customer, so add the sites you actually care about - a strict one
    refuses exits that Cloudflare's own pages serve happily.

.PARAMETER Pick
    Reconnect the best exit when the sweep is done and hold it. Without this,
    the sweep leaves nothing connected.

.PARAMETER Timeout
    Seconds to wait for a handshake before writing the config off. Default 15,
    which is about three times what a server that is going to answer takes -
    the address was already probed a moment earlier, so a config that has not
    finished by then is not slow, it is not coming. Raise it on a bad line.

    It is a ceiling, not a schedule. A server that answers in three seconds
    costs three seconds; the timeout is only ever paid by the ones that never
    answer. The same goes for every other wait in here - the route changing,
    the tunnel going down - all of which are watched for and none of which are
    slept through.

.PARAMETER NoOwner
    Do not look up who owns each exit. The lookup names the hosting company
    behind an address - M247, Datacamp, Leaseweb - which is usually the thing
    a site is blocking when it blocks "a VPN", so exits sharing one tend to be
    flagged together. It costs one HTTP request per exit and, on the free tier
    of the service that answers it, that request is not encrypted.

.PARAMETER AuthFile
    An OpenVPN auth file - username on the first line, password on the second.
    Most pinned configs carry a bare `auth-user-pass`, which makes openvpn.exe
    stop and ask; a sweep that stops and asks 169 times is not a sweep. Given
    nothing, .ovpn-auth and then .env are used if they are there, and failing
    that you are asked once and the answer goes to a temporary file that is
    deleted at the end.

.PARAMETER NoAuth
    Do not supply credentials at all. Only sensible for certificate-only
    servers, which never ask.

.PARAMETER Wsl
    Run `ovpn-connect.sh --sweep` inside WSL instead of doing any of this
    natively. Worth knowing before you do:

      - The tunnel exists inside WSL only. Windows itself is not on the VPN
        while this runs, which for a measurement is fine - the probes run in
        there too - but do not expect your browser to be affected.
      - It needs openvpn inside the distro (`sudo apt install openvpn`) and it
        will ask for your sudo password.
      - -Name and -Site are passed through. The rest of the flags here do not
        exist on that side.

.EXAMPLE
    .\Sweep-OvpnExits.ps1 -OnePer -First 10
    .\Sweep-OvpnExits.ps1 -Name de- -Site chatgpt.com,github.com
    .\Sweep-OvpnExits.ps1 -OnePer -Pick
    .\Sweep-OvpnExits.ps1 -Wsl -Name de-fra
#>
[CmdletBinding()]
param(
    [string]   $Name,
    [string]   $PinnedDir,
    [string]   $SuccessDir,
    [string[]] $Site,

    [string[]] $Landlord,
    [switch]   $PickLandlord,

    [switch]   $OnePer,
    [int]      $First,
    [switch]   $Pick,

    [ValidateRange(5, 300)]
    [int]      $Timeout = 15,

    [switch]   $NoOwner,

    [string]   $OpenVpn,
    [string]   $AuthFile,
    [string]   $EnvFile,
    [switch]   $NoAuth,
    [switch]   $Force,

    [switch]   $Wsl,
    [string]   $WslDistro,

    # Set when this script relaunched itself with administrator rights. Keeps
    # the new window open at the end, since nobody is watching the old one.
    [switch]   $Elevated
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$root = Split-Path -Parent $PSCommandPath

# The probes, the verdict vocabulary and the [ ok ] / [fail] writers all live
# in the pinner already. Copying them here would mean two things to keep in
# step, and they would drift.
. (Join-Path $root 'Resolve-OvpnRemote.ps1') -AsLibrary


#--------------------------------------------------------------------- helpers

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Find-OpenVpn {
    param([string] $Hint)
    $candidates = @()
    if ($Hint) { $candidates += $Hint }
    $candidates += @(
        (Join-Path $env:ProgramFiles 'OpenVPN\bin\openvpn.exe')
        (Join-Path ${env:ProgramFiles(x86)} 'OpenVPN\bin\openvpn.exe')
        (Join-Path $env:ProgramW6432 'OpenVPN\bin\openvpn.exe')
    )
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return (Resolve-Path $c).Path }
    }
    $cmd = Get-Command openvpn.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

# A VPN that is already up breaks this in three separate ways, and every one
# of them looks like "all the servers are dead" rather than like its cause:
# its kill switch blocks openvpn's own connection to the address, its routes
# fight the ones openvpn installs, and if the tunnel does come up the traffic
# may still leave the old way - so the measurement would be of the other VPN's
# exit. Better to say so once than to fail forty times.
function Test-ExistingTunnel {
    $hop = Get-DefaultHop
    if ($hop -and $hop.IsTunnel) { return $hop }
    $null
}

function Get-FreePort {
    $l = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    $l.Start()
    $p = $l.LocalEndpoint.Port
    $l.Stop()
    $p
}

# Shorter than the pinner's, because a sweep asks this once per config and a
# dead address should cost seconds, not most of a minute.
function Test-Port {
    param([string] $Ip, [int] $Port, [int] $TimeoutMs = 5000)
    $c = New-Object Net.Sockets.TcpClient
    try { return $c.ConnectAsync($Ip, $Port).Wait($TimeoutMs) }
    catch { return $false }
    finally { $c.Close() }
}

function Read-Remote {
    param([IO.FileInfo] $File)
    $lines = Get-Content -LiteralPath $File.FullName
    $r = Get-RemoteLines $lines | Select-Object -First 1
    if (-not $r) { return $null }
    [pscustomobject]@{
        Ip       = $r.Host
        Port     = Get-RemotePort  $r.Tail $lines
        Proto    = Get-RemoteProto $r.Tail $lines
        WantsAuth = [bool]($lines -match '^\s*auth-user-pass\s*$')
    }
}


#------------------------------------------------------------------ landlords

# "1,3" / "1-4" / "2,5-7" -> the numbers meant, with anything out of range
# dropped rather than silently treated as something else.
function Select-Indexes {
    param([string] $Spec, [int] $Max)
    $out = @()
    foreach ($part in ($Spec -split '[,\s]+' | Where-Object { $_ })) {
        if ($part -match '^(\d+)\s*-\s*(\d+)$') {
            $a = [int]$Matches[1]; $b = [int]$Matches[2]
            if ($a -le $b) { $out += $a..$b } else { $out += $b..$a }
        }
        elseif ($part -match '^\d+$') { $out += [int]$part }
    }
    @($out | Where-Object { $_ -ge 1 -and $_ -le $Max } | Select-Object -Unique | Sort-Object)
}

# file name -> the owner of the address it dials. One batch lookup for the
# lot, answered from .state\owners.tsv when it has been asked before.
function Get-ConfigOwners {
    param($Configs)
    $ips = @()
    $ipOf = @{}
    foreach ($c in $Configs) {
        $r = Read-Remote $c
        if ($r) { $ipOf[$c.Name] = $r.Ip; $ips += $r.Ip }
    }
    $owners = Get-IpOwner @($ips | Select-Object -Unique) $null

    $out = @{}
    foreach ($k in $ipOf.Keys) {
        $o = $owners[$ipOf[$k]]
        if ($o) { $out[$k] = $o }
    }
    $out
}

function Show-LandlordMenu {
    param($Configs, [hashtable] $Owners)

    $groups = @($Configs |
        Where-Object { $Owners[$_.Name] } |
        Group-Object { Format-Owner $Owners[$_.Name] } |
        Sort-Object Count -Descending)

    if (-not $groups) {
        Write-Warn 'nothing could be looked up, so there is nothing to choose between.'
        return $null
    }

    Write-Head 'Who these are rented from'
    for ($i = 0; $i -lt $groups.Count; $i++) {
        $countries = @($groups[$i].Group | ForEach-Object { $Owners[$_.Name].Country } |
                       Where-Object { $_ } | Select-Object -Unique)
        $where = if ($countries.Count -le 3) { $countries -join ', ' } else { "$($countries.Count) countries" }
        $word = if ($groups[$i].Count -eq 1) { 'config ' } else { 'configs' }
        Write-Host ('    {0,2}  {1,-30} {2,4} {3}   {4}' -f ($i + 1), $groups[$i].Name, $groups[$i].Count, $word, $where)
    }

    $unknown = @($Configs | Where-Object { -not $Owners[$_.Name] }).Count
    Write-Host ''
    if ($unknown) { Write-Warn "$unknown config(s) could not be looked up - choosing any landlord leaves them out." }
    Write-Info 'Addresses under one company are close to one address, as far as being'
    Write-Info 'blocked goes. Picking a few of these and sweeping those is usually a'
    Write-Info 'better hour than sweeping everything.'
    Write-Host ''

    $spec = Read-Host '         which ones? numbers, e.g. 1,3 or 1-4 (enter for all)'
    if (-not $spec.Trim()) { return $null }

    $picked = Select-Indexes $spec $groups.Count
    if (-not $picked) {
        Write-Warn 'nothing in that answer named a landlord on the list - taking all of them.'
        return $null
    }

    $names = @($picked | ForEach-Object { $groups[$_ - 1].Name })
    Write-Info "chosen: $($names -join ', ')"
    $names
}


#------------------------------------------------------------------------ auth

# openvpn.exe asks on the console when a config says `auth-user-pass` with no
# file after it, and a prompt in the middle of an unattended sweep is a hang
# with extra steps. So the credentials get settled once, before anything is
# connected.
function Resolve-AuthFile {
    param([string] $Explicit, [string] $EnvPath, [ref] $Temporary)

    if ($Explicit) {
        if (-not (Test-Path $Explicit)) { throw "auth file not found: $Explicit" }
        return (Resolve-Path $Explicit).Path
    }

    $existing = Join-Path $root '.ovpn-auth'
    if (Test-Path $existing) {
        Write-Info "credentials: $existing"
        return $existing
    }

    # Same two keys the Linux side reads, so one .env serves both.
    $envFile = if ($EnvPath) { $EnvPath } else { Join-Path $root '.env' }
    if (Test-Path $envFile) {
        $u = $null; $p = $null
        # UTF-8, not the ANSI code page: a password with a non-ASCII character
        # read through the wrong one is a password that does not work, and the
        # server's answer to that says nothing about why.
        foreach ($line in [IO.File]::ReadAllLines($envFile, [Text.Encoding]::UTF8)) {
            if ($line -match '^\s*OVPN_USER\s*=\s*(.*)$') { $u = $Matches[1].Trim(" `t'`"") }
            if ($line -match '^\s*OVPN_PASS\s*=\s*(.*)$') { $p = $Matches[1].Trim(" `t'`"") }
        }
        if ($u -and $p) {
            $f = New-AuthFile $u $p
            $Temporary.Value = $true
            Write-Info "credentials: from $envFile"
            return $f
        }
    }

    Write-Host ''
    Write-Info 'These configs ask for a username and password. Give them once here'
    Write-Info 'and the sweep runs unattended; they go to a temporary file that is'
    Write-Info 'deleted when it finishes.'
    $u = Read-Host '         provider username'
    $sec = Read-Host '         provider password' -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
    try { $p = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }

    if (-not $u -or -not $p) { throw 'no credentials given - rerun with -NoAuth if the server does not want any' }
    $Temporary.Value = $true
    New-AuthFile $u $p
}

# Written outside the repo, and readable by this account only - the whole
# point of a password file is undone by leaving it world-readable in a folder
# you later zip up and send to someone.
function New-AuthFile {
    param([string] $User, [string] $Pass)
    $f = Join-Path $env:TEMP ("ovpn-sweep-{0}.auth" -f [guid]::NewGuid().ToString('N'))
    [IO.File]::WriteAllText($f, "$User`n$Pass`n", (New-Object Text.UTF8Encoding($false)))

    $acl = Get-Acl $f
    $acl.SetAccessRuleProtection($true, $false)
    $acl.SetAccessRule((New-Object Security.AccessControl.FileSystemAccessRule(
        [Security.Principal.WindowsIdentity]::GetCurrent().Name, 'FullControl', 'Allow')))
    Set-Acl -Path $f -AclObject $acl
    $f
}


#--------------------------------------------------------------- the connexion

function Start-Tunnel {
    param([string] $Exe, [IO.FileInfo] $Config, [string] $LogPath, [int] $MgmtPort, [string] $Auth)

    if (Test-Path $LogPath) { Remove-Item $LogPath -Force }

    $a = @(
        '--config', "`"$($Config.FullName)`""
        '--log', "`"$LogPath`""
        '--management', '127.0.0.1', $MgmtPort
        '--verb', '3'
        '--connect-retry-max', '1'
        '--connect-retry', '2'
        '--auth-nocache'
    )
    # After --config, so it wins over the bare auth-user-pass inside it.
    if ($Auth) { $a += @('--auth-user-pass', "`"$Auth`"") }

    Start-Process -FilePath $Exe -ArgumentList $a -PassThru -WindowStyle Hidden `
                  -WorkingDirectory $Config.DirectoryName
}

# openvpn keeps its log open for writing for as long as it runs, and
# [IO.File]::ReadAllText asks the filesystem for a share mode that excludes an
# existing writer - so against a live openvpn it does not return a short read
# or an empty string, it throws, every single time. A caller that treats that
# as "nothing in the log yet" then waits out the entire timeout and reports no
# handshake for a tunnel that came up in four seconds, which is exactly what
# this did until the logs and the results table were laid side by side.
function Read-LogText {
    param([string] $Path, [int] $Tail = 256KB)
    $fs = New-Object IO.FileStream($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    try {
        # A server that resets the connection in a loop can write a lot of log
        # in fifteen seconds, and this is read every couple of hundred
        # milliseconds. Only the end of it is ever interesting, and a bounded
        # read cannot be talked into allocating whatever the far end feels
        # like.
        if ($fs.Length -gt $Tail) { [void] $fs.Seek(-$Tail, [IO.SeekOrigin]::End) }
        (New-Object IO.StreamReader($fs)).ReadToEnd()
    }
    finally { $fs.Dispose() }
}

# Returns 'up', or the reason it is not. Reads the log rather than the
# management socket because the log is what a person will want to look at
# afterwards anyway, and one source of truth beats two.
function Wait-Tunnel {
    param($Process, [string] $LogPath, [int] $Seconds)

    $everRead = $false
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if ($Process.HasExited) { return 'openvpn exited' }
        # Tight enough that the poll interval is not a measurable part of how
        # long a config takes. The timeout is a limit, not a schedule.
        Start-Sleep -Milliseconds 200
        if (-not (Test-Path $LogPath)) { continue }
        $log = ''
        try { $log = Read-LogText $LogPath; $everRead = $true } catch { continue }

        if ($log -match 'Initialization Sequence Completed') { return 'up' }
        if ($log -match 'AUTH_FAILED')                       { return 'rejected the username or password' }
        if ($log -match 'TLS Error: TLS key negotiation failed') { return 'no TLS handshake' }
        # 2.6 and older open a TAP adapter; 2.7 opens a DCO device instead,
        # and says so in different words. Both mean the same thing here.
        if ($log -match 'Cannot open TUN/TAP|All TAP-Windows adapters .* are currently in use|Cannot open DCO device|ovpn-dco device .*(fail|error)') {
            return 'no tunnel adapter available - is this running as administrator?'
        }
        if ($log -match 'Options error')                      { return 'openvpn would not accept the config' }
        if ($log -match 'Exiting due to fatal error')         { return 'fatal error - see the log' }
    }
    # Never having managed to read the log at all is a different failure from
    # having read it and found no handshake in it, and saying so is the
    # difference between a five minute diagnosis and an hour of one.
    if (-not $everRead) { return "could not read openvpn's log - $LogPath" }
    'no handshake in ' + $Seconds + 's'
}

# SIGTERM through the management socket, not Stop-Process. A killed openvpn
# leaves its routes and its DNS behind, and the next config then fails for a
# reason that looks like anything but that - which in a sweep would poison
# every result after the first one.
function Stop-Tunnel {
    param($Process, [int] $MgmtPort, [int] $TunnelIndex = 0)
    if (-not $Process -or $Process.HasExited) { return }
    try {
        $c = New-Object Net.Sockets.TcpClient('127.0.0.1', $MgmtPort)
        $w = New-Object IO.StreamWriter($c.GetStream())
        $w.AutoFlush = $true
        $w.WriteLine('signal SIGTERM')
        Start-Sleep -Milliseconds 150
        $c.Close()
    }
    catch { }
    if (-not $Process.WaitForExit(12000)) {
        Write-Warn 'openvpn ignored SIGTERM - killing it'
        try { Stop-Process -Id $Process.Id -Force } catch { }
        $Process.WaitForExit(5000) | Out-Null
    }

    # The routes usually go before the process does - openvpn deletes them on
    # the way out - but "usually" is not something to start the next connect
    # on. So watch for the default route to stop being this tunnel's rather
    # than sleeping a flat two seconds and hoping. Nothing came up, nothing to
    # wait for.
    if ($TunnelIndex) { Wait-RouteAway $TunnelIndex 3 | Out-Null }
}

# Poll for the default route to move off an interface, and stop the moment it
# has. Returns whether it did.
function Wait-RouteAway {
    param([int] $Index, [int] $Cap = 3)
    $deadline = (Get-Date).AddSeconds($Cap)
    while ((Get-Date) -lt $deadline) {
        $h = Get-DefaultHop
        if (-not $h -or $h.Index -ne $Index) { return $true }
        Start-Sleep -Milliseconds 150
    }
    $false
}

# And the other way round: wait for it to arrive somewhere new. Which
# interface it lands on does not matter here - that it is no longer the one it
# was does, and that test needs no guessing at adapter names.
function Wait-RouteOnto {
    param([int] $WasIndex, [int] $Cap = 5)
    $deadline = (Get-Date).AddSeconds($Cap)
    while ((Get-Date) -lt $deadline) {
        $h = Get-DefaultHop
        if ($h -and $h.Index -ne $WasIndex) { return $h }
        Start-Sleep -Milliseconds 150
    }
    Get-DefaultHop
}


#--------------------------------------------------------------------- kept

# A config that came up is worth keeping hold of, and how long it took to come
# up is worth keeping with it: when a tunnel drops at an awkward moment, the
# question is not which exit is cleanest, it is which one is quickest to have
# working again. Sorted by name, this folder answers that.
#
# Zero-padded, because a plain alphabetical sort - which is what every file
# manager and every shell does by default - puts "10s" before "4s" otherwise,
# and a list that lies about its own order is worse than no list.
# 04.2s-de-fra..._146.70.160.213.ovpn -> de-fra..._146.70.160.213.ovpn. The
# success folder can itself be swept, so a name arriving here may already
# carry a time from last time; without this the prefixes would stack up -
# 04.2s-11.8s-03.9s- - and every table keyed on the filename would treat the
# same config as a different one each round.
function Get-BaseConfigName {
    param([string] $Name)
    $Name -replace '^\d{1,3}(\.\d+)?s-', ''
}

# Anything that would be a nuisance in a filename, turned into dashes. Owner
# names arrive as "M247 AS9009" and "Cyberzone S.A." and neither belongs in a
# path as it stands.
function Get-NameTag {
    param([string] $Text, [int] $Max = 28)
    $t = ($Text -replace '[^A-Za-z0-9]+', '-').Trim('-')
    if ($t.Length -gt $Max) { $t = $t.Substring(0, $Max).TrimEnd('-') }
    if (-not $t) { $t = 'unknown' }
    $t
}

function Save-Successful {
    param([IO.FileInfo] $Config, [double] $Seconds, [string] $Dir, [string] $Tag)

    if (-not (Test-Path $Dir)) { New-Item -ItemType Directory -Path $Dir | Out-Null }

    $base = Get-BaseConfigName $Config.Name
    $name = if ($Tag) { '{0:00.0}s-{1}-{2}' -f $Seconds, $Tag, $base }
            else      { '{0:00.0}s-{1}'     -f $Seconds, $base }
    $dest = Join-Path $Dir $name

    # Copy before deleting, and never delete what is being copied: when the
    # folder being swept is this one, the source file is the previous run's
    # entry for this very config.
    if ($Config.FullName -ne $dest) {
        Copy-Item -LiteralPath $Config.FullName -Destination $dest -Force
    }

    # Swept again, a config replaces its own old entry rather than sitting
    # beside it under a different time - or, in the landlord folder, under a
    # different landlord, which is a thing that does change when a provider
    # moves a location to a new host.
    Get-ChildItem -LiteralPath $Dir -Filter "*-$base" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ne $name } |
        Remove-Item -Force -ErrorAction SilentlyContinue

    $name
}

# Re-sweeping the success folder is a re-test of things that used to work, so
# one that no longer connects should not keep sitting in a folder that claims
# otherwise. Only ever the copy - the original in pinned\ is untouched.
function Remove-Successful {
    param([IO.FileInfo] $Config, [string] $Dir)
    $base = Get-BaseConfigName $Config.Name
    $any = $false
    # The labelled copy goes with it. Two folders saying different things
    # about the same config is worse than either of them being out of date.
    foreach ($d in @($Dir, (Join-Path $Dir 'landlord'))) {
        if (-not (Test-Path $d)) { continue }
        $gone = @(Get-ChildItem -LiteralPath $d -Filter "*-$base" -File -ErrorAction SilentlyContinue)
        if ($gone) {
            $gone | Remove-Item -Force -ErrorAction SilentlyContinue
            $any = $true
        }
    }
    $any
}


#--------------------------------------------------------------------- verdict

# The shape of ovpn-lib.sh's cf_exit_verdict, so both halves of the repo file
# the same words in the same table.
function Get-ExitVerdict {
    param([string[]] $Extra)

    $trace = Get-CfTrace
    if ($trace.Probe.Error) {
        return [pscustomobject]@{ Verdict = 'unreachable'; Exit = ''; Loc = ''
                                  Good = 0; Bad = 0; Detail = $trace.Probe.Error }
    }

    $egress = $trace.Fields['ip']
    $loc    = $trace.Fields['loc']
    $good = 0; $bad = 0; $detail = @()

    if ($trace.Probe.Verdict -eq 'ok') { $good++ }
    else { $bad++; $detail += 'cloudflare.com itself' }

    foreach ($s in (($Extra -join ',') -split '[,\s]+' | Where-Object { $_ })) {
        $h = $s -replace '^https?://', '' -replace '/.*$', ''
        if ($h -notmatch '^[A-Za-z0-9._-]+$') { continue }
        $p = try { Invoke-CfProbe "https://$h/" 15000 }
             catch { [pscustomobject]@{ Verdict = 'unreachable'; Error = $_.Exception.Message } }
        switch ($p.Verdict) {
            'ok'         { $good++ }
            'challenged' { $bad++; $detail += "$h challenged" }
            'blocked'    { $bad++; $detail += "$h 403" }
            default      { $detail += "$h $($p.Verdict)" }
        }
    }

    $verdict = if ($bad -eq 0 -and $good -gt 0) { 'clean' }
               elseif ($good -eq 0)             { 'dirty' }
               else                             { 'partly' }

    [pscustomobject]@{
        Verdict = $verdict
        Exit    = $egress
        Loc     = $loc
        Good    = $good
        Bad     = $bad
        Detail  = if ($detail) { $detail -join ', ' } else { "$good served" }
    }
}

# file <TAB> ip <TAB> verdict <TAB> checked <TAB> detail, keyed on the file.
# LF endings and a plain tab: this table is read by awk on the other side.
function Write-ExitRow {
    param([string] $File, [string] $Ip, [string] $Verdict, [string] $Detail)

    $dir = Join-Path $root '.state'
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    $tsv = Join-Path $dir 'exits.tsv'

    $rows = @()
    if (Test-Path $tsv) {
        # Written as UTF-8 below, so read as UTF-8 here. Get-Content's default
        # is the ANSI code page, and a detail line with a non-ASCII character
        # in it would come back as the mojibake of its own bytes and grow every
        # time the table was rewritten - which is exactly what happened to the
        # owners table.
        $rows = @([IO.File]::ReadAllLines($tsv, [Text.Encoding]::UTF8) | Where-Object {
            $_ -and (($_ -split "`t")[0] -ne $File)
        })
    }
    $rows += ($File, $Ip, $Verdict, (Get-Date -Format 'yyyy-MM-dd'), $Detail) -join "`t"
    [IO.File]::WriteAllText($tsv, ($rows -join "`n") + "`n", (New-Object Text.UTF8Encoding($false)))
}


#------------------------------------------------------------------------- wsl

function Invoke-WslSweep {
    param([string] $Distro, [string] $Filter, [string[]] $Sites)

    Write-Head 'Sweeping from WSL'

    $wsl = Get-Command wsl.exe -ErrorAction SilentlyContinue
    if (-not $wsl) { Write-Bad 'WSL is not installed on this machine.'; return 1 }

    if (-not $Distro) {
        # -l -q comes back UTF-16 with the odd stray null; squeeze it out
        # rather than trusting the console encoding to have been set.
        $list = (& $wsl.Source -l -q) -join "`n"
        $list = ($list -replace "`0", '') -split '\r?\n' | Where-Object { $_.Trim() }
        $Distro = ($list | Where-Object { $_ -notmatch 'docker' } | Select-Object -First 1)
        if (-not $Distro) { Write-Bad 'no WSL distribution to run this in.'; return 1 }
        $Distro = $Distro.Trim()
    }
    Write-Info "distribution: $Distro"

    $has = (& $wsl.Source -d $Distro -- bash -lc 'command -v openvpn || true') -replace "`0", ''
    if (-not ($has -match 'openvpn')) {
        Write-Bad "openvpn is not installed inside $Distro."
        Write-Info 'Install it there and run this again:'
        Write-Host ''
        Write-Host "         wsl -d $Distro -- sudo apt update" -ForegroundColor White
        Write-Host "         wsl -d $Distro -- sudo apt install -y openvpn" -ForegroundColor White
        Write-Host ''
        return 1
    }

    # C:\Users\x\ovpn-pin -> /mnt/c/Users/x/ovpn-pin. Spelled out rather than
    # done with a script block in -replace, which Windows PowerShell 5.1 -
    # the one this repo targets - does not have.
    $lin = '/mnt/' + $root.Substring(0, 1).ToLower() + ($root.Substring(2) -replace '\\', '/')

    Write-Info 'The tunnel will exist inside WSL only - Windows itself stays off the'
    Write-Info 'VPN while this runs. The probes run in there too, so the verdicts are'
    Write-Info 'about the exit, and they land in the same .state\exits.tsv.'
    Write-Info 'It will ask for your sudo password.'
    Write-Host ''

    $cmd = "cd '$lin' && bash ./ovpn-connect.sh --sweep"
    if ($Filter) { $cmd += " '$Filter'" }
    if ($Sites)  { $cmd += " --site '" + (($Sites -join ',') -replace "'", '') + "'" }

    & $wsl.Source -d $Distro -- bash -lc $cmd
    $LASTEXITCODE
}


#------------------------------------------------------------------------ main

try {
    Write-Host ''
    Write-Host '  Sweep-OvpnExits' -ForegroundColor White
    Write-Host '  connects each pinned config in turn and judges its exit' -ForegroundColor DarkGray

    if (-not $PinnedDir)  { $PinnedDir  = Join-Path $root 'pinned' }
    if (-not $SuccessDir) { $SuccessDir = Join-Path $root 'success' }

    # Sweeping the success folder is a re-test of what worked last time rather
    # than a survey of everything, and it behaves slightly differently: a
    # config that no longer connects is taken out of it, because a folder that
    # says these all work should not be quietly wrong. GetFullPath rather than
    # Resolve-Path, which wants the folder to exist first.
    $retesting = ([IO.Path]::GetFullPath($PinnedDir).TrimEnd('\')) -eq
                 ([IO.Path]::GetFullPath($SuccessDir).TrimEnd('\'))

    if ($Wsl) { exit (Invoke-WslSweep $WslDistro $Name $Site) }

    if (-not (Test-Path $PinnedDir)) {
        Write-Head 'Nothing to sweep'
        if ($retesting) {
            Write-Info "There is no $PinnedDir yet - nothing has been found to work so far."
            Write-Info 'Sweep the pinned folder first; whatever connects lands in there,'
            Write-Info 'and then it is worth re-testing on its own.'
        }
        else {
            Write-Info "There is no $PinnedDir yet. Run Resolve-OvpnRemote.ps1 first -"
            Write-Info 'it writes the pinned configs this connects to.'
        }
        Write-Host ''
        exit 1
    }

    $exe = Find-OpenVpn $OpenVpn
    if (-not $exe) {
        Write-Head 'No openvpn.exe'
        Write-Info 'This drives the OpenVPN command line client, and it is not on this'
        Write-Info 'machine. OpenVPN Connect and the GUI cannot be scripted, so having'
        Write-Info 'one of those installed does not help here.'
        Write-Host ''
        Write-Host '         winget install --id OpenVPNTechnologies.OpenVPN' -ForegroundColor White
        Write-Host ''
        Write-Info 'It installs alongside anything you already have and changes nothing'
        Write-Info 'about it. Then run this again.'
        Write-Host ''
        Write-Info 'Or, without installing anything on Windows:'
        Write-Host ''
        Write-Host '         .\Sweep-OvpnExits.ps1 -Wsl' -ForegroundColor White
        Write-Host ''
        exit 2
    }

    #---------------------------------------------------------- what to sweep --

    $all = @(Get-ChildItem -LiteralPath $PinnedDir -Filter *.ovpn -File | Sort-Object Name)
    $configs = $all
    if ($Name)   { $configs = @($configs | Where-Object { $_.Name -like "*$Name*" }) }

    # By landlord, before anything else narrows it: choosing two companies out
    # of twenty is a bigger cut than anything below, and the choice is only
    # meaningful while the whole list is still on the table.
    $ownerOf = @{}
    if (($Landlord -or $PickLandlord) -and $configs) {
        Write-Head 'Landlords'
        Write-Info "looking up who $($configs.Count) addresses are rented from"
        $ownerOf = Get-ConfigOwners $configs

        $wanted = @(($Landlord -join ',') -split '[,]+' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
        if ($PickLandlord) {
            $chosen = Show-LandlordMenu $configs $ownerOf
            if ($chosen) { $wanted = $chosen }
        }

        if ($wanted) {
            $configs = @($configs | Where-Object {
                $o = $ownerOf[$_.Name]
                if (-not $o) { return $false }
                $hay = Format-Owner $o
                foreach ($w in $wanted) { if ($hay -like "*$w*") { return $true } }
                $false
            })
            Write-Info "$($configs.Count) config(s) are rented from those"
        }
    }

    if ($OnePer) {
        # de-fra.prod.surfshark.com_tcp_146.70.160.237.ovpn -> de-fra...com_tcp
        $configs = @($configs | Group-Object { (Get-BaseConfigName $_.Name) -replace '_[0-9.]+\.ovpn$', '' } |
                     ForEach-Object { $_.Group | Select-Object -First 1 })
    }
    if ($First -gt 0) { $configs = @($configs | Select-Object -First $First) }

    if (-not $configs) {
        Write-Head 'Nothing to sweep'
        $why = @()
        if ($Name)     { $why += "the name '$Name'" }
        if ($Landlord) { $why += "the landlord '$($Landlord -join ", ")'" }
        Write-Info "No pinned config matches$(if ($why) { ' ' + ($why -join ' and ') }). There are $($all.Count) in $PinnedDir."
        Write-Host ''
        exit 1
    }

    $other = Test-ExistingTunnel
    if ($other -and -not $Force) {
        Write-Head 'Another VPN is already up'
        Write-Bad "traffic currently leaves over $($other.Alias)$(if ($other.Description) { " [$($other.Description)]" })"
        Write-Host ''
        Write-Info 'Turn it off before sweeping. Two tunnels at once do not stack, they'
        Write-Info 'fight, and all three ways it goes wrong look identical from here -'
        Write-Info 'like every server being dead:'
        Write-Info '  - its kill switch or firewall blocks openvpn from reaching the'
        Write-Info '    address at all, so every config is written off as unreachable;'
        Write-Info '  - its routes and the ones openvpn installs contradict each other;'
        Write-Info '  - the tunnel comes up but traffic still leaves the old way, so'
        Write-Info '    what gets measured is the other VPN''s exit, not this one''s.'
        Write-Host ''
        Write-Info 'Disconnect it and run this again. -Force sweeps anyway, if you are'
        Write-Info 'certain this adapter is not what it looks like.'
        Write-Host ''
        exit 4
    }

    $mins = [math]::Ceiling($configs.Count * 0.25)
    Write-Head "Sweep ($($configs.Count) of $($all.Count) configs)"
    Write-Info 'Each one is connected for real, judged, and dropped again. That is the'
    Write-Info 'only way this can be known. Nothing here waits on a clock - every step'
    Write-Info 'moves on the moment it is done - so a config that answers costs ten'
    Write-Info "seconds or so and one that has to time out costs $Timeout. Reckon on"
    Write-Info "$mins minutes, longer if a lot of them are dead."
    if ($Site) { Write-Info "also testing: $($Site -join ', ')" }
    Write-Info 'Your connection drops in and out for the duration. Ctrl+C stops it.'

    if (-not $Force -and $configs.Count -gt 12) {
        Write-Host ''
        $go = Read-Host "         that is a long time. Continue? [y/N]"
        if ($go -notmatch '^(y|yes)$') {
            Write-Host ''
            Write-Info 'Nothing swept. -OnePer tests one address per location instead,'
            Write-Info '-PickLandlord lets you drop whole hosting companies, and -First N'
            Write-Info 'stops after N of them.'
            Write-Host ''
            exit 0
        }
    }

    #------------------------------------------------------------ administrator

    # Opening the tunnel adapter and adding routes both need it. Asked for
    # here rather than at the top so that everything you have to decide is
    # decided first: a UAC prompt before you have chosen anything, followed by
    # a new window that asks you the same questions again, is a worse deal.
    # The choices go with it as flags, so the elevated run picks the same set
    # without asking twice.
    if (-not (Test-Admin)) {
        Write-Head 'Needs administrator'
        Write-Info 'A new elevated window is opening - the sweep runs there, with what'
        Write-Info 'you chose above.'
        Write-Host ''

        $argv = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"",
                  '-Elevated', '-Force')
        # The resolved landlords, not -PickLandlord: the list has been chosen
        # and the elevated window has no business asking again.
        if ($wanted) { $argv += @('-Landlord', "`"$($wanted -join ',')`"") }

        foreach ($kv in $PSBoundParameters.GetEnumerator()) {
            if ($kv.Key -in 'Elevated', 'Force', 'PickLandlord', 'Landlord') { continue }
            if ($kv.Value -is [switch]) {
                if ($kv.Value.IsPresent) { $argv += "-$($kv.Key)" }
            }
            elseif ($kv.Value -is [array]) { $argv += @("-$($kv.Key)", "`"$($kv.Value -join ',')`"") }
            else { $argv += @("-$($kv.Key)", "`"$($kv.Value)`"") }
        }

        try { Start-Process powershell.exe -Verb RunAs -ArgumentList $argv | Out-Null; exit 0 }
        catch { Write-Bad 'elevation was refused - nothing was swept.'; exit 3 }
    }

    #-------------------------------------------------------------- baseline --

    Write-Head 'Before connecting anything'
    $base = Get-CfTrace
    $baseIp = $base.Fields['ip']
    if ($baseIp) { Write-Info "Cloudflare sees you as $baseIp$(if ($base.Fields['loc']) { " in $($base.Fields['loc'])" }) right now" }
    else { Write-Warn 'Cloudflare did not answer before we started - verdicts may be about your line, not the exits.' }

    #------------------------------------------------------------------ auth --

    $authTemp = $false
    $auth = $null
    if (-not $NoAuth) {
        $needsAuth = $false
        foreach ($c in $configs) {
            $r = Read-Remote $c
            if ($r -and $r.WantsAuth) { $needsAuth = $true; break }
        }
        if ($needsAuth) { $auth = Resolve-AuthFile $AuthFile $EnvFile ([ref] $authTemp) }
    }

    #----------------------------------------------------------------- sweep --

    $logDir = Join-Path $env:TEMP 'ovpn-sweep'
    if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

    $results = @()
    $n = 0
    foreach ($cfg in $configs) {
        $n++
        Write-Host ''
        $r = Read-Remote $cfg
        if (-not $r) {
            Write-Warn "$($cfg.Name)  has no remote line - skipped"
            continue
        }
        # Everything downstream is keyed on the name without the time in
        # front, so that a config gets one row whether it was swept from
        # pinned\ or from success\.
        $base = Get-BaseConfigName $cfg.Name
        Write-Info ("[{0}/{1}] {2}  {3}:{4} {5}" -f $n, $configs.Count, $base, $r.Ip, $r.Port, $r.Proto)

        # A dead address costs five seconds here instead of a whole handshake
        # timeout. UDP cannot be probed this way - openvpn drops any datagram
        # without a valid HMAC, so silence means blocked and working equally -
        # so those go straight to the connect.
        if ($r.Proto -match '^tcp' -and -not (Test-Port $r.Ip $r.Port)) {
            Write-Bad 'the address does not answer - skipped'
            if ($retesting -and (Remove-Successful $cfg $SuccessDir)) {
                Write-Info 'dropped from success\ - it does not answer any more'
            }
            $results += [pscustomobject]@{ Name = $base; Path = $cfg.FullName; Verdict = 'unreachable'; Exit = '-'; Owner = ''; Seconds = 0; Detail = 'address does not answer' }
            Write-ExitRow $base $r.Ip 'unreachable' 'address does not answer'
            continue
        }

        $log  = Join-Path $logDir ($cfg.BaseName + '.log')
        $port = Get-FreePort
        $proc = $null
        $tunIndex = 0
        $t0 = Get-Date
        try {
            $wasHop = Get-DefaultHop
            $wasIndex = if ($wasHop) { $wasHop.Index } else { 0 }

            $proc = Start-Tunnel $exe $cfg $log $port $auth
            $state = Wait-Tunnel $proc $log $Timeout

            if ($state -ne 'up') {
                Write-Bad ("did not come up - $state [{0:n1}s]" -f ((Get-Date) - $t0).TotalSeconds)
                Write-Info "log: $log"
                if ($retesting -and (Remove-Successful $cfg $SuccessDir)) {
                    Write-Info 'dropped from success\ - it does not connect any more'
                }
                $results += [pscustomobject]@{ Name = $base; Path = $cfg.FullName; Verdict = 'noconnect'; Exit = '-'; Owner = ''; Seconds = 0; Detail = $state }
                Write-ExitRow $base $r.Ip 'noconnect' $state
                continue
            }

            # Timed to here rather than to the end of the measuring: this is
            # what you would wait through if you connected it yourself.
            $secs = ((Get-Date) - $t0).TotalSeconds
            $kept = Save-Successful $cfg $secs $SuccessDir
            Write-Ok ("up in {0:n1}s - kept as {1}" -f $secs, $kept)

            # The routes go in a moment before the handshake is announced, and
            # DNS a moment after; probing on the same breath measures the
            # changeover rather than the exit. Watched for rather than slept
            # through, so a machine that switches over in 200ms is not held
            # for two seconds to prove it.
            $hop = Wait-RouteOnto $wasIndex 5
            if ($hop) { $tunIndex = $hop.Index }

            $v = Get-ExitVerdict $Site

            # Same address as before we connected means the traffic never went
            # into the tunnel - a verdict on that is a verdict on your own
            # line, and reporting it as the exit's would be a lie.
            #
            # But the address alone cannot say that, so the routing table gets
            # the last word. The two disagreeing is worth printing rather than
            # flattening: a route through the tunnel and an unchanged exit
            # address is not "no route", it is a measurement that did not
            # arrive the way it left, and the two want different fixing.
            if ($baseIp -and $v.Exit -eq $baseIp) {
                if ($hop -and $hop.IsTunnel) {
                    $why = "the exit address did not change, though the route goes over $($hop.Alias)"
                    Write-Warn "$why - not judged"
                    Write-Info 'The tunnel is carrying traffic; something answered from outside it.'
                }
                else {
                    $why = 'traffic did not enter the tunnel'
                    Write-Warn "the tunnel is up but $why - not judged"
                    if ($hop) { Write-Info "traffic still leaves over $($hop.Alias)" }
                }
                $results += [pscustomobject]@{ Name = $base; Path = $cfg.FullName; Verdict = 'noroute'; Exit = $v.Exit; Owner = ''; Seconds = $secs; Detail = $why }
                Write-ExitRow $base $r.Ip 'noroute' $why
                continue
            }

            $where = "$($v.Exit)$(if ($v.Loc) { " $($v.Loc)" })"
            $took = ' [{0:n1}s]' -f ((Get-Date) - $t0).TotalSeconds
            switch ($v.Verdict) {
                'clean'  { Write-Ok   "clean     exit $where  ($($v.Good) served)$took" }
                'partly' { Write-Warn "partly    exit $where  ($($v.Good) served, $($v.Bad) refused: $($v.Detail))$took" }
                'dirty'  { Write-Bad  "flagged   exit $where  (everything refused)$took" }
                default  { Write-Warn "$($v.Verdict)  $($v.Detail)$took" }
            }

            # Who the exit is rented from. Done here rather than up front
            # because the exit is rarely the address you dialled - providers
            # NAT it - so this is the only moment the real one is known.
            $owner = ''
            if (-not $NoOwner -and $v.Exit) {
                $o = (Get-IpOwner @($v.Exit) $null)[$v.Exit]
                if ($o) {
                    $owner = Format-Owner $o
                    Write-Info "rented from $owner$(if ($o.City) { " in $($o.City)" })"

                    # A second copy, in success\landlord, labelled with who
                    # the exit belongs to and which country it came out in.
                    # Same file, same timing, sorted differently on purpose:
                    # this one answers "what have I got from M247, and where",
                    # which is the question when a whole hosting company turns
                    # out to be blocked and you need the same country from
                    # somebody else's racks.
                    #
                    # The country is Cloudflare's idea of where the exit is,
                    # not the provider's label on the file - de-fra is where
                    # they say it is, DE is where it came out.
                    $loc = if ($v.Loc) { $v.Loc } else { 'xx' }
                    $tag = '{0}-{1}' -f (Get-NameTag $owner), (Get-NameTag $loc 6)
                    $also = Save-Successful $cfg $secs (Join-Path $SuccessDir 'landlord') $tag
                    Write-Info "also kept as landlord\$also"
                }
            }

            $detail = if ($owner) { "$($v.Detail) [$owner]" } else { $v.Detail }
            $results += [pscustomobject]@{ Name = $base; Path = $cfg.FullName; Verdict = $v.Verdict; Exit = $where; Owner = $owner; Seconds = $secs; Detail = $v.Detail }
            Write-ExitRow $base $r.Ip $v.Verdict $detail
        }
        catch {
            # One config blowing up is one result, not the end of the sweep.
            # Half an hour of measurements should not be lost to whatever the
            # thirtieth config managed to do.
            Write-Bad "error on this config - $($_.Exception.Message)"
            $results += [pscustomobject]@{ Name = $base; Path = $cfg.FullName; Verdict = 'error'; Exit = '-'; Owner = ''
                                           Seconds = 0; Detail = $_.Exception.Message }
            Write-ExitRow $base $r.Ip 'error' $_.Exception.Message
        }
        finally {
            Stop-Tunnel $proc $port $tunIndex
        }
    }

    #--------------------------------------------------------------- results --

    Write-Head 'Sweep results'

    $order = @{ clean = 0; partly = 1; dirty = 2 }
    $sorted = $results | Sort-Object @{ Expression = { if ($order.ContainsKey($_.Verdict)) { $order[$_.Verdict] } else { 3 } } }, Name

    $best = $null
    foreach ($x in $sorted) {
        $line = '{0,-46} {1,-22} ' -f $x.Name, $x.Exit
        switch ($x.Verdict) {
            'clean'  { Write-Ok   ($line + 'clean');  if (-not $best) { $best = $x } }
            'partly' { Write-Warn ($line + "partly - $($x.Detail)"); if (-not $best) { $best = $x } }
            'dirty'  { Write-Bad  ($line + 'flagged - Cloudflare refuses it') }
            default  { Write-Info ($line + "$($x.Verdict) - $($x.Detail)") }
        }
    }

    # The pattern worth reading off a sweep is rarely about one address. If
    # every exit under one company is refused and every exit under another is
    # served, that is the finding - it says which of your remaining, unswept
    # configs are worth trying at all.
    $byOwner = @($results | Where-Object { $_.Owner } | Group-Object Owner | Sort-Object Count -Descending)
    if ($byOwner.Count -gt 1) {
        Write-Head 'By landlord'
        foreach ($g in $byOwner) {
            $c = @($g.Group | Where-Object { $_.Verdict -eq 'clean' }).Count
            $p = @($g.Group | Where-Object { $_.Verdict -eq 'partly' }).Count
            $d = @($g.Group | Where-Object { $_.Verdict -eq 'dirty' }).Count
            $bits = @()
            if ($c) { $bits += "$c clean" }
            if ($p) { $bits += "$p partly" }
            if ($d) { $bits += "$d flagged" }
            $line = '{0,-30} {1}' -f $g.Name, ($bits -join ', ')
            if ($c)      { Write-Ok   $line }
            elseif ($p)  { Write-Warn $line }
            else         { Write-Bad  $line }
        }
    }

    # Forty identical failures are one fact, not forty, and the shape of them
    # says which fact it is. Reading that off the table is exactly the job
    # somebody would otherwise do by hand at one in the morning.
    $judged = @($results | Where-Object { $_.Verdict -in 'clean', 'partly', 'dirty' }).Count
    if ($results.Count -gt 1 -and $judged -eq 0) {
        $auth    = @($results | Where-Object { $_.Detail -match 'username or password' }).Count
        $unreach = @($results | Where-Object { $_.Verdict -eq 'unreachable' }).Count
        $noroute = @($results | Where-Object { $_.Verdict -eq 'noroute' }).Count
        $adapter = @($results | Where-Object { $_.Detail -match 'tunnel adapter' }).Count

        Write-Head 'Not one of them connected'
        if ($auth -eq $results.Count) {
            Write-Bad 'every server refused the username and password.'
            Write-Info 'That is one problem, not forty - and it is a good sign about'
            Write-Info 'everything else: the address, the route and the certificate all'
            Write-Info 'worked, or the server would never have got as far as refusing you.'
            Write-Info 'Check the credentials in .env, or delete .ovpn-auth if it holds an'
            Write-Info 'old pair. Some providers want a service username here rather than'
            Write-Info 'the one you log into their website with.'
        }
        elseif ($adapter -gt 0) {
            Write-Bad 'openvpn could not open a tunnel adapter.'
            Write-Info 'Nothing to do with the servers. Run this as administrator, and'
            Write-Info 'make sure no other VPN client is holding the adapter.'
        }
        elseif ($noroute -eq $results.Count) {
            Write-Bad 'the tunnels came up but no traffic went into them.'
            Write-Info 'Something else owns the default route - almost always another VPN'
            Write-Info 'client still running. Turn it off and sweep again.'
        }
        elseif ($unreach -gt ($results.Count / 2)) {
            Write-Bad 'not one address answered on its port.'
            Write-Info 'That is not forty dead servers. Either something local is in the'
            Write-Info 'way - another VPN, a kill switch, a firewall - or the addresses'
            Write-Info 'have moved on: providers rotate them, and a pinned file cannot'
            Write-Info 'follow. Pin them again (option 1) and sweep the fresh ones.'
        }
        else {
            Write-Bad 'every config failed, in more than one way.'
            Write-Info "The logs say why, one per config: $logDir"
        }
        Write-Host ''
    }

    # What connected, quickest first. The verdict table above answers "which
    # exit is clean"; this one answers "which one comes up fastest", and they
    # are different questions with different winners.
    $up = @($results | Where-Object { $_.Seconds -gt 0 } | Sort-Object Seconds)
    if ($up) {
        Write-Head 'Quickest to connect'
        foreach ($x in ($up | Select-Object -First 10)) {
            Write-Info ('{0,6:n1}s  {1,-46} {2}' -f $x.Seconds, $x.Name, $x.Verdict)
        }
        if ($up.Count -gt 10) { Write-Info "... and $($up.Count - 10) more" }
        Write-Host ''
        Write-Info "All $($up.Count) that connected are in $SuccessDir, named by how long"
        Write-Info 'they took - sort that folder by name and the top of it is what to'
        Write-Info 'reach for when a tunnel drops.'

        $lord = Join-Path $SuccessDir 'landlord'
        $lordCount = @(Get-ChildItem -LiteralPath $lord -Filter *.ovpn -File -ErrorAction SilentlyContinue).Count
        if ($lordCount) {
            Write-Info "The $lordCount whose exit could be traced to a hosting company are also"
            Write-Info "in $lord, with the company and the country in the name."
        }
    }

    Write-Host ''
    Write-Info "written to $(Join-Path $root '.state\exits.tsv')"

    if (-not $best) {
        Write-Host ''
        Write-Bad 'not one of these exits is served by Cloudflare.'
        Write-Info 'Re-pin for fresh addresses - providers rotate them - or get configs'
        Write-Info 'for other locations from your provider.'
        Write-Host ''
    }
    elseif ($Pick) {
        Write-Head "Connecting the best of them"
        # The file it was swept from, not a path rebuilt out of the name: the
        # two differ whenever the folder being swept is success\, where the
        # names carry a time in front.
        $cfg  = Get-Item -LiteralPath $best.Path
        $port = Get-FreePort
        $log  = Join-Path $logDir ($cfg.BaseName + '.log')
        $proc = Start-Tunnel $exe $cfg $log $port $auth
        $state = Wait-Tunnel $proc $log $Timeout
        if ($state -eq 'up') {
            Write-Ok "$($best.Name)  -  exit $($best.Exit), $($best.Verdict)"
            Write-Host ''
            Read-Host '         connected. Press Enter to disconnect' | Out-Null
        }
        else { Write-Bad "it would not come up this time - $state" }
        Stop-Tunnel $proc $port
        Write-Info 'disconnected.'
        Write-Host ''
    }
    else {
        Write-Host ''
        Write-Info "Best of them: $($best.Name)"
        Write-Info 'Nothing is connected now - import that one into your client, or run'
        Write-Info 'this again with -Pick to have it connected at the end.'
        Write-Host ''
    }
}
catch {
    Write-Host ''
    Write-Bad $_.Exception.Message
    Write-Host ''
    if ($Elevated) { Read-Host 'Press Enter to close' | Out-Null }
    exit 1
}
finally {
    if ($authTemp -and $auth -and (Test-Path $auth)) { Remove-Item $auth -Force -ErrorAction SilentlyContinue }
}

if ($Elevated) { Read-Host '         Press Enter to close' | Out-Null }
