# Reads the Shortcut table out of an .msi, with nothing installed, and fails unless the package puts
# a shortcut on the desktop and one in the Start menu (B-186).
#
#   pwsh scripts/check_msi_shortcuts.ps1 ui/build/compose/binaries/main-release/msi/kachok-0.1.<n>.msi
#
# Why it reads the database rather than installing: a runner has no desktop anybody looks at, and an
# .msi is a database whose tables say what an install would do - B-90 read the Upgrade table the
# same way. Windows only: the WindowsInstaller COM object is the reader.
#
# A shortcut's directory is resolved through the Directory table up to its root, so a Start menu
# entry inside the `menuGroup` folder (ProgramMenuFolder\kachok) counts as a Start menu entry.

param([Parameter(Mandatory = $true)][string] $Msi)

$ErrorActionPreference = 'Stop'

function Invoke-Com($target, [string] $name, [System.Reflection.BindingFlags] $kind, [object[]] $arguments) {
    $target.GetType().InvokeMember($name, $kind, $null, $target, $arguments)
}

$method = [System.Reflection.BindingFlags]::InvokeMethod
$property = [System.Reflection.BindingFlags]::GetProperty

function Get-Rows($database, [string] $sql, [int] $columns) {
    $view = Invoke-Com $database 'OpenView' $method @($sql)
    Invoke-Com $view 'Execute' $method $null | Out-Null
    $rows = @()
    while ($true) {
        $record = Invoke-Com $view 'Fetch' $method $null
        if ($null -eq $record) { break }
        $rows += , @(1..$columns | ForEach-Object { Invoke-Com $record 'StringData' $property @($_) })
    }
    Invoke-Com $view 'Close' $method $null | Out-Null
    , $rows
}

$path = (Resolve-Path $Msi).Path
$installer = New-Object -ComObject WindowsInstaller.Installer
# 0 = msiOpenDatabaseModeReadOnly
$database = Invoke-Com $installer 'OpenDatabase' $method @($path, 0)

$tables = (Get-Rows $database 'SELECT `Name` FROM `_Tables`' 1) | ForEach-Object { $_[0] }
$parents = @{}
foreach ($row in (Get-Rows $database 'SELECT `Directory`, `Directory_Parent` FROM `Directory`' 2)) {
    $parents[$row[0]] = $row[1]
}

function Get-Chain([string] $directory) {
    $chain = @()
    $seen = @{}
    while ($directory -and -not $seen.ContainsKey($directory)) {
        $seen[$directory] = $true
        $chain += $directory
        $directory = $parents[$directory]
    }
    $chain
}

Write-Output "Shortcut table of $(Split-Path $path -Leaf):"
$shortcuts = @()
if ($tables -contains 'Shortcut') {
    $shortcuts = Get-Rows $database 'SELECT `Shortcut`, `Directory_`, `Name`, `Component_` FROM `Shortcut`' 4
}
if ($shortcuts.Count -eq 0) {
    Write-Output '  (no rows - the table is empty or absent)'
}
$desktop = $false
$menu = $false
foreach ($row in $shortcuts) {
    $chain = Get-Chain $row[1]
    Write-Output ("  {0}  in {1}  name '{2}'  component {3}" -f $row[0], ($chain -join ' < '), $row[2], $row[3])
    if ($chain -contains 'DesktopFolder') { $desktop = $true }
    if ($chain -contains 'ProgramMenuFolder' -or $chain -contains 'StartMenuFolder') { $menu = $true }
}

$missing = @()
if (-not $desktop) { $missing += 'a desktop shortcut (DesktopFolder)' }
if (-not $menu) { $missing += 'a Start menu entry (ProgramMenuFolder)' }
if ($missing.Count -gt 0) {
    Write-Output "The package makes no $($missing -join ', and no ')."
    exit 1
}
Write-Output 'A desktop shortcut and a Start menu entry: both present.'
