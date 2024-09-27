@echo off
echo Breaktest command deck App is starting...
start /high /B java -jar commmand-deck.jar > app.log
%SystemRoot%\System32\timeout.exe 0
%SystemRoot%\System32\timeout.exe 10
start http://localhost:8080
echo Appliacation started
@echo on