@echo off
rem ---------------------------------------------------------------------------
rem  Double-click launcher for Resolve-OvpnRemote.ps1.
rem
rem  Windows will not run a .ps1 on a double-click, and running one from a
rem  shell usually stops at the execution policy - so this starts PowerShell
rem  itself with -ExecutionPolicy Bypass, which affects this one run and
rem  changes nothing on the machine. It also keeps the window open at the end,
rem  because a double-clicked script that fails closes before you can read why.
rem
rem  Arguments are passed straight through, so this works as a shortcut too:
rem      run.cmd -CheckCloudflare -Site chatgpt.com
rem      run.cmd -h
rem ---------------------------------------------------------------------------

setlocal
cd /d "%~dp0"

set "SCRIPT=%~dp0Resolve-OvpnRemote.ps1"
set "SWEEP=%~dp0Sweep-OvpnExits.ps1"

if not exist "%SCRIPT%" (
    echo.
    echo   [fail] Resolve-OvpnRemote.ps1 is not next to this file.
    echo          Keep run.cmd in the ovpn-pin folder.
    echo.
    pause
    exit /b 1
)

rem Asked for the options: print them and stop, without starting PowerShell.
if /i "%~1"=="-h"     goto :helponly
if /i "%~1"=="-help"  goto :helponly
if /i "%~1"=="--help" goto :helponly
if /i "%~1"=="/?"     goto :helponly
if /i "%~1"=="/h"     goto :helponly

rem Called with options: run them and get out of the way.
if not "%~1"=="" (
    call :run %*
    echo.
    pause
    exit /b %errorlevel%
)

:menu
cls
echo.
echo   ovpn-pin
echo   ========
echo.
echo   A downloaded .ovpn points at a name. On a censored line your resolver
echo   answers that name with a fake address, so OpenVPN dials nowhere. This
echo   looks the name up over DNS-over-HTTPS instead and writes the real
echo   address into the config. Your originals in configs\ are never touched;
echo   the fixed copies come out in pinned\ - import those into OpenVPN.
echo.
echo     1  Get the real IPs and write the fixed configs
echo          Reads every file in configs\, looks it up over DoH, writes
echo          pinned\. Then pings each address and prints reachable or not,
echo          which is how you see which locations are usable. Start here.
echo.
echo     2  The same, but do the lookups through a proxy
echo          Only if 1 could not look anything up - meaning DoH itself is
echo          blocked, not just ordinary DNS. Needs your proxy app running.
echo          The proxy is used for the lookup only, not for connecting.
echo.
echo     3  Judge the VPN you are connected to right now
echo          Connect first with one of the pinned files, then run this. It
echo          asks Cloudflare what it makes of that exit - some VPN IPs are
echo          blocked by half the web while the tunnel itself is perfectly
echo          fine. Pins nothing. Useless while disconnected.
echo.
echo     4  Test every location, one after another
echo          Connects to each pinned config for real, asks Cloudflare what
echo          it makes of that exit, drops it, next. The slow answer, and
echo          the only true one. Every config that comes up is copied into
echo          success\ with the time it took in front of its name, so that
echo          folder sorted by name is your list of what works, fastest
echo          first - and into success\landlord\ with the hosting company
echo          and the country it came out in on the name as well.
echo          Needs openvpn.exe (it tells you how) or WSL. Leaves nothing
echo          connected unless you ask.
echo.
echo     5  Who owns these addresses
echo          Names the company the server is actually rented from - M247,
echo          Datacamp, Clouvider - and where it sits. That is the name a
echo          site sees when it blocks "a VPN", so addresses sharing one
echo          tend to be blocked together. Connects to nothing, takes
echo          seconds, and is remembered afterwards.
echo.
echo     6  Open the pinned\ folder
echo     7  Run it with options you type yourself
echo     h  Show every option
echo     0  Quit
echo.

set "choice="
set /p "choice=  choose [1]: "
if not defined choice set "choice=1"

if "%choice%"=="1" goto :pin
if "%choice%"=="2" goto :pinproxy
if "%choice%"=="3" goto :cloudflare
if "%choice%"=="4" goto :sweep
if "%choice%"=="5" goto :whois
if "%choice%"=="6" goto :openpinned
if "%choice%"=="7" goto :custom
if /i "%choice%"=="h" goto :helpmenu
if "%choice%"=="0" exit /b 0
goto :menu

rem ---------------------------------------------------------------- actions --

:pin
call :run
goto :again

:pinproxy
echo.
echo   The proxy carries the DoH lookups only. Nothing about connecting later
echo   depends on it, because the address ends up written into the config.
echo   Common ports: v2rayN 10809, Clash 7890, Nekoray 2080, Hiddify 12334.
echo.
set "proxy="
set /p "proxy=  proxy [http://127.0.0.1:10808]: "
if not defined proxy set "proxy=http://127.0.0.1:10808"
call :run -Proxy "%proxy%"
goto :again

:cloudflare
echo.
echo   This measures the connection you have this second, so it only means
echo   anything while the VPN is up. Extra sites are optional - Cloudflare's
echo   rules are per-customer, so a strict site can refuse an exit that
echo   Cloudflare's own pages serve happily.
echo.
set "sites="
set /p "sites=  extra sites, comma separated (enter for none): "
if defined sites (
    call :run -CheckCloudflare -Site "%sites%"
) else (
    call :run -CheckCloudflare
)
goto :again

:sweep
echo.
echo   This connects to each config in turn and measures what the web does
echo   with that exit - the thing no amount of looking at your own line can
echo   tell you. Your connection drops in and out while it runs, and it asks
echo   for administrator rights, because opening a tunnel needs them.
echo.
echo   Which folder?
echo.
echo     1  pinned\    everything you have. The full survey.
echo     2  success\   only the ones that connected last time, re-tested.
echo                   Much shorter, and it is the honest way to find out
echo                   whether yesterday's good list is still good. Anything
echo                   in there that has stopped connecting is taken out.
echo.
set "swdir="
set /p "swdir=  folder [1]: "
set "swdirarg="
if "%swdir%"=="2" set "swdirarg=-PinnedDir "%~dp0success""
echo.
echo   Three ways to answer the next question:
echo.
echo     enter        one address per location. The fast way round, and the
echo                  one to start with: it answers "which places work" in
echo                  about a seventh of the time. Re-testing success\, it
echo                  means all of it - that list is short already.
echo     a name       part of one - de-, us-lax, nl - and every address of
echo                  the configs that match gets tested.
echo     all          every pinned address there is. A provider gives you
echo                  several per location, so this is hours, not minutes.
echo                  It tells you which addresses within a good location
echo                  are the good ones - worth it once, not every time.
echo.
echo   It says how long before starting, and asks before committing you.
echo.
set "swname="
set /p "swname=  which ones? (enter, a name, or all): "
set "swsites="
set /p "swsites=  extra sites to test, comma separated (enter for none): "

echo.
echo   You can also throw out whole hosting companies first. Your addresses
echo   are rented from about twenty of them, and one company's addresses are
echo   near enough one address as far as being blocked goes - so choosing a
echo   few companies to test is usually a better half hour than testing
echo   everything. It shows you the list and you pick numbers.
echo.
set "swland="
set /p "swland=  choose by landlord first? [y/N]: "

rem Plain sequential overrides rather than nested if/else - quotes inside a
rem parenthesised block in batch are their own kind of afternoon.
set "swargs="
if not defined swname set "swargs=-OnePer"
if defined swname set "swargs=-Name "%swname%""
rem "all" means no filter at all: not one per location, not a name.
if /i "%swname%"=="all" set "swargs="
if /i "%swname%"=="*" set "swargs="
rem Re-testing success\ is already a short list of known-good configs, so an
rem empty answer there means all of them rather than one per location.
if "%swdir%"=="2" if not defined swname set "swargs="
if defined swsites set "swargs=%swargs% -Site "%swsites%""
if /i "%swland%"=="y" set "swargs=%swargs% -PickLandlord"
if defined swdirarg set "swargs=%swargs% %swdirarg%"

echo.
echo   1  natively, with openvpn.exe
echo   2  from WSL, with the Linux script
echo.
set "swhow="
set /p "swhow=  how? [1]: "
if "%swhow%"=="2" set "swargs=%swargs% -Wsl"

call :runsweep %swargs%
goto :again

:whois
call :run -WhoIs
goto :again

:openpinned
if exist "%~dp0pinned" (
    start "" explorer "%~dp0pinned"
) else (
    echo.
    echo   [warn] no pinned folder yet - run 1 first.
    echo.
    pause
)
goto :menu

:custom
call :options
set "opts="
set /p "opts=  options: "
if not defined opts goto :menu
call :run %opts%
goto :again

:helpmenu
cls
call :options
echo.
pause
goto :menu

:helponly
call :options
exit /b 0

rem ------------------------------------------------------------------ options --

:options
echo.
echo   Options
echo   -------
echo   Everything below works on the menu's option 5, and on the run.cmd line
echo   itself - so a desktop shortcut to
echo       run.cmd -CheckCloudflare -Site chatgpt.com
echo   does that one job and nothing else.
echo.
echo     -Path X            a .ovpn file, or a folder of them. Default configs\
echo     -OutDir X          where the fixed copies go. Default pinned\
echo     -Proxy URL         proxy for the lookups, e.g. http://127.0.0.1:10808
echo     -Resolver X        who to ask: cloudflare (default) or google
echo     -MaxIps N          most files to write per config. Default 4. A name
echo                        with several servers gives you one file each -
echo                        alternatives, not a ranking.
echo     -InPlace           overwrite the input instead of writing copies
echo     -NoTest            skip the reachable check. Faster, tells you less.
echo     -CheckCloudflare   pin nothing - judge the exit you are on now
echo     -Site a,b          extra sites for that check, comma separated
echo     -WhoIs             pin nothing - name the company each pinned
echo                        address is rented from, and group them by it
echo     -h                 this list
echo.
echo   Examples
echo     run.cmd -MaxIps 2
echo     run.cmd -Path configs\de-fra.prod.surfshark.com_tcp.ovpn
echo     run.cmd -Proxy http://127.0.0.1:10809
echo     run.cmd -CheckCloudflare -Site chatgpt.com,github.com
echo.
echo   Testing every location - menu option 4, or Sweep-OvpnExits.ps1 directly
echo   -----------------------------------------------------------------------
echo     -PickLandlord      list the hosting companies behind these configs
echo                        and sweep only the ones you pick. Numbers, so
echo                        1,3 or 1-4 or 2,5-7
echo     -Landlord a,b      the same without the list: -Landlord M247,CDN77
echo     -OnePer            one address per location, not all four of them
echo     -Name X            only configs whose filename contains X
echo     -First N           stop after N of them
echo     -Site a,b          extra sites to test on each exit
echo     -Pick              connect the best one when it is done and hold it
echo     -Timeout N         seconds to wait for a handshake. Default 15
echo     -NoOwner           skip the "rented from" lookup on each exit
echo     -SuccessDir X      where the ones that connected are copied, with
echo                        the handshake time on the front of the name.
echo                        Default success\
echo     -AuthFile X        username on line 1, password on line 2. Without
echo                        it, .ovpn-auth or .env is used, or you are asked
echo                        once and it goes to a temp file
echo     -NoAuth            supply no credentials at all
echo     -Force             skip the "this will take a while" question
echo     -Wsl               run the Linux sweep inside WSL instead
echo.
echo     powershell -ExecutionPolicy Bypass -File Sweep-OvpnExits.ps1 -OnePer
exit /b 0

rem ------------------------------------------------------------------ plumbing

:run
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
set "rc=%errorlevel%"
if not "%rc%"=="0" (
    echo.
    echo   [fail] the script exited with code %rc%
)
exit /b %rc%

:runsweep
if not exist "%SWEEP%" (
    echo.
    echo   [fail] Sweep-OvpnExits.ps1 is not next to this file.
    exit /b 1
)
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SWEEP%" %*
set "rc=%errorlevel%"
if not "%rc%"=="0" (
    echo.
    echo   [fail] the sweep exited with code %rc%
)
exit /b %rc%

:again
echo.
echo   ------------------------------------------------------------------
set "back="
set /p "back=  enter for the menu, q to quit: "
if /i "%back%"=="q" exit /b 0
goto :menu
