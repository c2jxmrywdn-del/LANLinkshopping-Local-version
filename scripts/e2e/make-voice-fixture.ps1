# 生成样例语音 wav fixture（Windows SAPI，微软 Huihui 中文女声）。
# 用途：asr-service 真实转写 pytest 与语音链路 E2E 的输入音频（无外部依赖、可重复生成）。
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File scripts\e2e\make-voice-fixture.ps1
param(
    [string]$OutPath = "$PSScriptRoot\fixtures\sample-voice-27s.wav",
    [int]$SampleRate = 16000,
    [int]$Rate = -1
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech

$text = '您好，我想咨询一下订单的问题。我昨天下午下了一单商品，选择的是标准物流，可是到现在' +
        '订单状态一直没有更新，物流信息也查不到。麻烦帮我看看这一单是什么情况，是不是发货' +
        '延误了，如果有的话我想尽快处理，谢谢您。'

$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$synth.SelectVoice('Microsoft Huihui Desktop')
$synth.Rate = $Rate
$synth.SetOutputToWaveFile($OutPath,
    [System.Speech.AudioFormat.SpeechAudioFormatInfo]::new(
        $SampleRate,
        [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
        [System.Speech.AudioFormat.AudioChannel]::Mono))
$synth.Speak($text)
$synth.Dispose()

# 校验 RIFF 头并回显时长（Windows SAPI 会写 LIST/INFO 块，data 块偏移不固定，需扫描）
$bytes = [System.IO.File]::ReadAllBytes($OutPath)
if ([System.Text.Encoding]::ASCII.GetString($bytes, 0, 4) -ne 'RIFF') {
    throw "not a RIFF wav: $OutPath"
}
$dataLen = 0
for ($i = 12; $i -lt $bytes.Length - 8; $i++) {
    if ([System.Text.Encoding]::ASCII.GetString($bytes, $i, 4) -eq 'data') {
        $dataLen = [BitConverter]::ToInt32($bytes, $i + 4)
        break
    }
}
$seconds = [math]::Round($dataLen / ($SampleRate * 2.0), 2)
Write-Output ("wav fixture written: $OutPath (sampleRate=$SampleRate, seconds=$seconds)")
