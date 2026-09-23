# Tyme4All overlay for TrainingPeaks Virtual. Polls the phone's relay once a second and shows
# VE, zone, BR and TV in a small always-on-top panel. Display only: it never starts or stops
# anything on the phone. PowerShell 5.1, WPF, no dependencies.
param([string]$Url)

Add-Type -AssemblyName PresentationFramework, PresentationCore, WindowsBase, System.Net.Http
[System.Reflection.Assembly]::LoadWithPartialName('Microsoft.VisualBasic') | Out-Null

$configDir  = Join-Path $env:APPDATA 'Tyme4All'
$configPath = Join-Path $configDir 'overlay.json'
$config = @{ url = ''; hasPos = $false; x = 0; y = 0; opacity = 0.7 }
if (Test-Path $configPath) {
  try {
    $saved = Get-Content $configPath -Raw | ConvertFrom-Json
    foreach ($k in 'url', 'hasPos', 'x', 'y', 'opacity') { if ($null -ne $saved.$k) { $config[$k] = $saved.$k } }
  } catch { }
}
if ($Url) { $config.url = $Url }

function Save-Config {
  New-Item -ItemType Directory -Force -Path $configDir | Out-Null
  $config | ConvertTo-Json | Set-Content -Path $configPath -Encoding UTF8
}
function Ask-Url([string]$current) {
  $v = [Microsoft.VisualBasic.Interaction]::InputBox('Paste the overlay URL shown on the phone Status tab', 'Tyme4All overlay', $current)
  if ($v) { return $v.Trim() } else { return $current }
}
if (-not $config.url) { $config.url = Ask-Url ''; if (-not $config.url) { exit }; Save-Config }

$zoneColors = '#424242', '#4db6ac', '#0277bd', '#f57f17', '#ef6c00', '#c62828'
$zoneNames  = '--', 'Z1', 'Z2', 'Z3', 'Z4', 'Z5'

[xml]$xaml = @"
<Window xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
        xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
        Title="Tyme4All" Width="260" Height="140" WindowStyle="None" AllowsTransparency="True"
        Background="Transparent" Topmost="True" ShowInTaskbar="False" ResizeMode="NoResize">
  <Border Name="Panel" CornerRadius="10" Background="#424242" Padding="12">
    <Grid>
      <Grid.RowDefinitions>
        <RowDefinition Height="Auto"/><RowDefinition Height="*"/><RowDefinition Height="Auto"/>
      </Grid.RowDefinitions>
      <StackPanel Orientation="Horizontal" Grid.Row="0">
        <Ellipse Name="Dot" Width="10" Height="10" Fill="#9e9e9e" Margin="0,0,6,0" VerticalAlignment="Center"/>
        <TextBlock Name="Status" Text="connecting" Foreground="#bdbdbd" FontSize="12"/>
      </StackPanel>
      <StackPanel Grid.Row="1" Orientation="Horizontal" VerticalAlignment="Center">
        <TextBlock Name="Ve" Text="--" Foreground="White" FontSize="44" FontWeight="Bold"/>
        <StackPanel VerticalAlignment="Bottom" Margin="8,0,0,8">
          <TextBlock Text="L/min" Foreground="#bdbdbd" FontSize="12"/>
          <TextBlock Name="Zone" Text="" Foreground="White" FontSize="14"/>
        </StackPanel>
      </StackPanel>
      <TextBlock Name="Row" Grid.Row="2" Text="BR --    TV --" Foreground="White" FontSize="16"/>
    </Grid>
  </Border>
</Window>
"@

$window = [Windows.Markup.XamlReader]::Load((New-Object System.Xml.XmlNodeReader $xaml))
$panel  = $window.FindName('Panel');  $dot  = $window.FindName('Dot');  $status = $window.FindName('Status')
$veText = $window.FindName('Ve');     $zone = $window.FindName('Zone'); $row    = $window.FindName('Row')
$window.Opacity = [double]$config.opacity
if ([bool]$config.hasPos) { $window.Left = [double]$config.x; $window.Top = [double]$config.y }
else {
  $wa = [System.Windows.SystemParameters]::WorkArea
  $window.Left = $wa.Right - $window.Width - 16; $window.Top = $wa.Top + 16
}

function Brush([string]$hex) { return [System.Windows.Media.BrushConverter]::new().ConvertFromString($hex) }

$script:failures = 0
function Render($p) {
  $script:failures = 0
  $live = $p.status -eq 'connected'
  if ($live -and $null -ne $p.ve) { $veText.Text = [string][math]::Round($p.ve) } else { $veText.Text = '--' }
  $br = '--'; $tv = '--'
  if ($live -and $null -ne $p.br) { $br = [string][math]::Round($p.br) }
  if ($live -and $null -ne $p.tv) { $tv = $p.tv.ToString('0.0', [System.Globalization.CultureInfo]::InvariantCulture) }
  $row.Text = "BR $br    TV $tv"
  $z = 0; if ($live -and $p.zone -ge 0 -and $p.zone -lt $zoneColors.Count) { $z = [int]$p.zone }
  $zone.Text = $(if ($live) { $zoneNames[$z] } else { '' })
  $panel.Background = Brush $zoneColors[$z]
  $status.Text = $p.status
  $dotColor = switch ($p.status) { 'connected' { '#66bb6a' } 'stale' { '#ffb300' } default { '#9e9e9e' } }
  $dot.Fill = Brush $dotColor
}
function RenderFailure {
  $script:failures++
  $status.Text = 'phone?'; $dot.Fill = Brush '#c62828'
  if ($script:failures -ge 5) { $panel.Background = Brush '#616161'; $veText.Text = '--'; $row.Text = 'BR --    TV --'; $zone.Text = '' }
}

$http = New-Object System.Net.Http.HttpClient
$http.Timeout = [TimeSpan]::FromMilliseconds(900)
$script:pending = $null
$timer = New-Object System.Windows.Threading.DispatcherTimer
$timer.Interval = [TimeSpan]::FromSeconds(1)
$timer.Add_Tick({
  if ($null -ne $script:pending) {
    if (-not $script:pending.IsCompleted) { return }
    $t = $script:pending; $script:pending = $null
    if ($t.Status -eq 'RanToCompletion') {
      try { Render ($t.Result | ConvertFrom-Json) } catch { RenderFailure }
    } else { RenderFailure }
  }
  try {
    $liveUrl = $config.url -replace '/overlay(\?|$)', '/live$1'
    $script:pending = $http.GetStringAsync($liveUrl)
  } catch { $script:pending = $null; RenderFailure }
})

$menu = New-Object System.Windows.Controls.ContextMenu
$mi = New-Object System.Windows.Controls.MenuItem; $mi.Header = 'Set phone URL...'
$mi.Add_Click({ $config.url = Ask-Url $config.url; Save-Config; $script:failures = 0; $script:pending = $null }); $menu.Items.Add($mi) | Out-Null
foreach ($o in 0.5, 0.7, 0.9) {
  $m = New-Object System.Windows.Controls.MenuItem; $m.Header = ('Opacity {0}%' -f [int]($o * 100)); $m.Tag = $o
  $m.Add_Click({ $config.opacity = [double]$this.Tag; $window.Opacity = [double]$this.Tag; Save-Config }); $menu.Items.Add($m) | Out-Null
}
$q = New-Object System.Windows.Controls.MenuItem; $q.Header = 'Quit'; $q.Add_Click({ $window.Close() }); $menu.Items.Add($q) | Out-Null
$window.ContextMenu = $menu

$window.Add_MouseLeftButtonDown({ $window.DragMove() })
$window.Add_Closing({ $config.hasPos = $true; $config.x = $window.Left; $config.y = $window.Top; Save-Config; $timer.Stop() })

$timer.Start()
$window.ShowDialog() | Out-Null
