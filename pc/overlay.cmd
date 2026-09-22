@echo off
rem Launches the K-Breathe overlay with the console hidden. Double-click, or make a shortcut to this file.
start "" powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0overlay.ps1" %*
