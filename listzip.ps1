Add-Type -AssemblyName System.IO.Compression.FileSystem
 = [0]
 = [1]
 = [System.IO.Compression.ZipFile]::OpenRead()
foreach ( in .Entries) {
   = .FullName
  if ([string]::IsNullOrEmpty() -or ( -match )) {
    Write-Output 
  }
}
.Dispose()
