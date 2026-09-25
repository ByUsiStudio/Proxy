# HTML 结构平衡检查：把 FreeMarker 指令剥离后，用栈匹配所有非空元素的开闭标签。
# 用法: pwsh -File check-tags.ps1
$dir = "D:\ByUsi\Projects\Proxy\proxy-server\src\main\resources\template\admin"
$void = @('area','base','br','col','embed','hr','img','input','link','meta','param','source','track','wbr')
$files = Get-ChildItem $dir -Filter *.ftl | Sort-Object Name
foreach ($f in $files) {
  $raw = Get-Content $f.FullName -Raw
  # 去掉 FreeMarker 注释与指令
  $t = [regex]::Replace($raw, '(?s)<#--.*?-->', '')
  $t = [regex]::Replace($t, '(?s)<#.*?>', '')
  # 去掉 script/style 内容
  $t = [regex]::Replace($t, '(?s)<script[^>]*>.*?</script>', '<script></script>')
  $t = [regex]::Replace($t, '(?s)<style[^>]*>.*?</style>', '<style></style>')
  # 去掉 HTML 注释
  $t = [regex]::Replace($t, '(?s)<!--.*?-->', '')
  # 记录行号：用一个等长替换把换行保留
  $stack = New-Object System.Collections.Stack
  $errors = @()
  $rx = [regex]'(?i)</?([a-zA-Z][a-zA-Z0-9]*)([^>]*?)(/?)>'
  foreach ($m in $rx.Matches($t)) {
    $tag = $m.Groups[1].Value.ToLower()
    $attrs = $m.Groups[2].Value
    $selfClose = $m.Groups[3].Value -eq '/'
    $isClose = $m.Value.StartsWith('</')
    if ($void -contains $tag -or $selfClose) { continue }
    if ($tag -eq 'script' -or $tag -eq 'style') { continue }
    if (-not $isClose) {
      $stack.Push($tag)
    } else {
      if ($stack.Count -eq 0) { $errors += "多余的 </$tag>"; continue }
      $top = $stack.Peek()
      if ($top -eq $tag) { [void]$stack.Pop() }
      else {
        $errors += "闭合不匹配: </$tag> 但当前打开的是 <$top>"
        # 尝试恢复：若栈中存在该标签则弹到它为止
        if ($stack.Contains($tag)) { while ($stack.Count -gt 0 -and $stack.Pop() -ne $tag) {} }
      }
    }
  }
  $left = @()
  while ($stack.Count -gt 0) { $left += $stack.Pop() }
  if ($errors.Count -eq 0 -and $left.Count -eq 0) {
    "{0,-14} OK" -f $f.Name
  } else {
    "{0,-14} 未闭合: [{1}]  错误: [{2}]" -f $f.Name, ($left -join ', '), ($errors -join ' | ')
  }
}
