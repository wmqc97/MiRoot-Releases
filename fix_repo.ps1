$file = "F:\Android\App\MiRoot2.1\app\src\main\java\com\wmqc\miroot\ui\music\LyricsSettingsRepository.kt"
$content = Get-Content $file -Encoding UTF8 -Raw
$content = $content -replace [regex]::Escape(".putBoolean(""wordByWord"", fixed.wordByWord)
            .putBoolean(""charJumpEnabled"", fixed.charJumpEnabled)
            .putFloat(""charJumpHeightPx"", fixed.charJumpHeightPx)"), ".putBoolean(""wordByWord"", fixed.wordByWord)
            .putBoolean(""charJumpEnabled"", fixed.charJumpEnabled)
            .putFloat(""charJumpHeightPx"", fixed.charJumpHeightPx)"
Set-Content $file -Value $content -Encoding UTF8
