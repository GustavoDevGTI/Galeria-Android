# Correções das falhas de validação — 01/10/2026

Esta etapa corrige as sete falhas registradas na validação da release 0.8.61. As alterações são posteriores àquela publicação e estão incluídas na versão 0.8.62; o APK 0.8.61 permanece inalterado. Nenhuma função foi removida, nenhum teste foi desativado e as verificações de destino, texto copiado, orientação, integridade dos arquivos e estado do player foram mantidas.

## Causas e correções

| Falha original | Diagnóstico | Correção |
| --- | --- | --- |
| Alternar cinema e abrir as trilhas | O aviso inicial de tela cheia do Android tomou o foco; o teste também procurava opções de PopupWindow na janela da Activity. | Preparar o aviso apenas no emulador e direcionar a busca à janela do popup. Permanecem as verificações de trilhas, player preservado, estado e barras do sistema. |
| Cinema persistente por álbum e recriação | O teste recriava a Activity enquanto a transição de orientação ainda estava em andamento. | Aguardar a conclusão da transição e recuperação do foco antes da recriação; manter a verificação da preferência por álbum e da saída temporária do cinema. |
| Submenu Editar | A abertura de uma ferramenta durante o fechamento do diálogo podia disputar o foco. A verificação posterior da foto girada também comparava literalmente URIs equivalentes de Images/Files do MediaStore. | Executar a ferramenta após dispensar o diálogo. O teste toca realmente nos três ícones e verifica EXIF e dimensões da imagem usando a identidade equivalente da mídia. |
| Giro de Motion Photo | Uma imagem sem a tag de orientação EXIF permanecia `UNDEFINED`; chamar `rotate(90)` nesse estado não aplicava a rotação esperada. | Inicializar a orientação normal somente quando ausente e aplicar o giro sem recomprimir. Validar preservação byte a byte do MP4 e primeira rotação de JPEG, PNG e WebP sem EXIF prévio. |
| OCR por toque prolongado | A leitura detalhada repetia as quatro orientações mesmo após reconhecer quase 2.000 caracteres do documento. A leitura automática anterior também ocupava o mesmo executor. | Interromper as tentativas extras somente diante de um trecho extenso e de alta confiança: pelo menos 12 palavras, pontuação mínima de 180 e confiança de 80%. Texto curto, baixa confiança e fragmentos de barra de status continuam exigindo outras orientações. |
| Copiar pelo ícone de OCR | O teste tentava tocar em `Copiar tudo` imediatamente após iniciar uma leitura assíncrona. | Aguardar o diálogo de resultado, mantendo a verificação do texto efetivamente copiado e do desaparecimento do ícone após substituir a imagem por uma sem texto. |
| Gestos após retorno ao app | O InputDispatcher registrou `Dropping untrusted touch event` causado por uma `Dim Layer for - Task` do sistema após a Activity temporária do ActivityScenario. A Activity já tinha foco, mas o Android ainda bloqueava o toque. | Aguardar a remoção dessa camada de entrada e a foto completamente exibida antes de injetar os gestos. Não desativar a proteção de toque nem simular a navegação por chamadas internas. Preservar todos os destinos esperados. |

## Execução reproduzível

Compilar uma vez e executar o runner compacto:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
.\scripts\run-android-tests.ps1 -All -Device emulator-5554
```

Repetir casos específicos sem reinstalar os mesmos APKs:

```powershell
.\scripts\run-android-tests.ps1 -Class 'com.galeria.android.VideoViewerRegressionTest' -Repeat 3 -SkipInstall
```

O runner conserva a saída integral em `app/build/reports/native-tests`, informa seu caminho quando há falha e imprime um resumo compacto. Isso permite consultar a causa original sem executar novamente o mesmo teste só para obter a pilha de erro. A preparação do aviso de tela cheia é restrita a dispositivos identificados pelo Android como emuladores; não altera configurações de um celular conectado.

Os logs temporários de diagnóstico foram removidos do aplicativo. Testes usam mídias próprias e não exigem limpar dados da galeria.

## Resultados finais

- Compilação debug e APK de testes: aprovadas, após remover os logs temporários.
- Testes unitários: **106/106 aprovados** pelo runner JUnit direto, executando o mesmo código compilado e as mesmas asserções; tempo de execução JUnit de 0,321 segundo.
- Lint debug: **zero erros, 125 avisos existentes**.
- Suíte completa no AVD `Galeria_Codex_Test_36`, Android API 36, SwiftShader: **87/87 aprovados em 311,1 segundos**. Inclui todos os sete casos que falharam na validação publicada e o novo teste de primeira rotação sem orientação EXIF.
- Repetição extra da classe `VideoViewerRegressionTest`: **5/5 aprovados em cada uma das três execuções** (53,5; 53,3; 55,1 segundos). Inclui reabertura, pausa/retorno, ambas as direções e eixos, cancelamento de toque, interrupção de animação e exclusão/restauração.
- Execução direcionada anterior de edição e navegação: 6/6 aprovados em 64,8 segundos.

Relatórios integrais locais: `app/build/reports/native-tests/20261001-121550-339-run-1.txt` (suíte completa) e `20261001-122112-215-run-{1,2,3}.txt` (repetições). Permanecem fora do Git por estarem no diretório de build. Nenhuma asserção foi removida ou teste ignorado para obter aprovação. A suíte foi executada antes do incremento dos metadados de versão, sobre a mesma base de código distribuída na 0.8.62. O APK release 0.8.61 já distribuído permanece intacto.

## Preparação da release 0.8.62

- Projeto transferido para `Y:\Sistemas\Gustavo Projetos\Galeria-Android`; compilação feita em cópia temporária local para evitar I/O de rede.
- Nova compilação release, 106 testes unitários e lint: aprovados. Lint permanece sem erros e com 125 avisos. Os testes unitários foram executados novamente após o incremento de versão.
- APK assinado verificado pelo `apksigner`, mantendo o certificado SHA-256 `1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce`.
- Metadados do APK: `versionName` 0.8.62, `versionCode` 8062.
- SHA-256 do APK: `72b22c37a69750039f3f330357ef06b74530cedffd1e4cfbe824eb7be0e8cad5`.
- Código, testes, documentação e release pertencem ao mesmo commit; APK publicado como anexo, não como novo binário versionado.

## Limites

Não houve teste em celular físico nesta etapa. A suíte automatizada não homologa todos os codecs, fabricantes, variantes de Motion Photos, idiomas ou combinações reais de trilhas de áudio e legenda. O histórico da validação do APK 0.8.61 continua disponível em [VALIDACAO-0.8.61.md](VALIDACAO-0.8.61.md).
