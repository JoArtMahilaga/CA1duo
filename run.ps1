javac -d target\classes (Get-ChildItem -Recurse -Filter *.java -Path src\main\java | ForEach-Object { $_.FullName })
Copy-Item -Force src\main\resources\index.html target\classes\index.html
Start-Process "http://localhost:8080"
java -cp target\classes server.GameExchangeServer
