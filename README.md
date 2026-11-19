# Fit Launcher PX5 — protótipo v0.6

Projeto Android independente em Kotlin para central Eonon GA2171SR / PX5, Android 9 (API 28), tela 1024×600, com tema de Honda Fit 2010 preto.

## Recursos atuais
- Painel horizontal com relógio, GPS e silhueta provisória do veículo.
- Atalhos para Waze, Maps, Spotify, aplicativos e ajustes.
- Seleção de adaptador Bluetooth clássico previamente pareado.
- ELM327 com consultas OBD-II somente leitura: RPM, temperatura, velocidade, carga e tensão.
- Tratamento de sensores não suportados e respostas inválidas.
- Testes JUnit do interpretador OBD2.

## Gerar o APK no GitHub
Acesse **Actions → Compilar Fit Launcher PX5 (APK)** e abra a execução mais recente. Se a compilação passar, baixe o artefato **FitLauncherPX5-v0.6-debug-APK**, extraia o ZIP e instale `app-debug.apk` para testes.

Também é possível acionar **Run workflow**.

## Precauções
Este é um protótipo de testes, ainda não validado na central.
- Não configure o aplicativo como launcher HOME padrão.
- Não altere o firmware ou a MCU.
- Teste parado, com o veículo em local ventilado.
- Feche o Car Scanner antes de usar a conexão OBD2.
- A comunicação implementada usa Bluetooth clássico SPP; se o ELM327 estiver usando BLE, precisará de adaptação.
- Recursos gráficos e código próprios; nenhum código proprietário do Rota Launcher ou Car Scanner foi incluído.

## Compilação manual
Requer JDK 17, Android SDK 34 e Gradle 8.2.1. Execute `gradle :app:testDebugUnitTest :app:assembleDebug`.

O APK de debug é gerado em `app/build/outputs/apk/debug/app-debug.apk`.
