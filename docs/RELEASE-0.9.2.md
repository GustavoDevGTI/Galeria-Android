# Galeria Android 0.9.2

## Alterações

- Escolha a origem e entre diretamente na roda, com lixeira fixa embaixo e até sete álbuns de destino.
- Configure os destinos arrastando álbuns entre a roda e a gaveta inferior, que abre por seta ou gesto, tem rolagem própria e não cobre a roda.
- Nova pasta é o primeiro item da gaveta, com miniatura **+**. Capas e nomes seguem o menu Mover.
- As mídias só são movidas ou excluídas ao tocar em **Salvar**. Trocar álbuns da roda preserva os destinos já marcados. Voltar oferece salvar, descartar ou continuar.
- A lixeira respeita a configuração: recuperável quando habilitada e exclusão permanente quando desabilitada, sempre após salvar.
- Desfazer fica acima da roda e cancela marcações não salvas. Uma nova tentativa de salvamento não repete arquivos já concluídos.
- A foto ampliada fica reta e mantém o fundo desfocado. Vídeos usam a mesma prévia e iniciam a reprodução com play/pausa central e progresso.
- A configuração anterior reaproveita até sete destinos. Reserva, pilha antecipada, contadores e temas continuam disponíveis.

## APK

- Arquivo: `Galeria-Android-versao-0.9.2.apk`
- Pacote: `com.galeria.android`
- Versão: `0.9.2` / código `9002`
- Certificado SHA-256: `1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce`

A assinatura permanente permite atualizar a versão release instalada preservando dados e preferências.

## Validação

A execução completa `20261023-163923-519-41ba17e2` aprovou **250 testes unitários, 148 instrumentados e 45 contratos das ferramentas**, sem falhas ou ignorados. Lint: zero erros e 142 avisos. A regressão completa durou 748,9 s, sendo 693,7 s no emulador Android 16/API 36. O recibo foi aceito por `assert-delivery-validation.ps1`.

Fontes compilados e do repositório conferidos: `7152E30DBB81AC198E4F46F05902F37C4FCFB9C551F5F1E8A5682893D2DEF3B1`. As evidências locais estão em `app/build/reports/validation/20261023-163923-519-41ba17e2/`.

A tentativa anterior `20261023-162313-219-93e98e56` foi rejeitada com 147/148 instrumentados: o teste do HUD selecionou uma janela invisível e sem foco depois de Voltar do editor. O caso isolado passou; o teste passou a aguardar as janelas realmente retomadas e com foco antes/depois de Voltar, mantendo as verificações e sem repetir o evento de navegação. A execução completa acima passou com essa sincronização.

A validação automatizada é feita no emulador com APK debug. A conferência complementar em aparelhos físicos e codecs de fabricantes continua necessária.

## Conferência do pacote

- Tamanho: 45.169.270 bytes
- SHA-256: `a86605be5aae02b2e948e157351fe63e5709d79a8d9129d8c19f890363abe2f6`
- Assinatura e certificado permanente verificados; pacote `com.galeria.android`, versão `0.9.2`, código `9002`, sem modo depurável.
- Build otimizada com R8 e redução de recursos; os três registradores OCR e seus construtores foram preservados.
