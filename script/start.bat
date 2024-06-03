@echo off
echo Webling Extension App is starting...
start /B java -jar weblingExtension.jar > app.log
%SystemRoot%\System32\timeout.exe 0
%SystemRoot%\System32\timeout.exe 10
start http://localhost:8080
echo Appliacation started
@echo on