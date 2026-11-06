# Galeria Android 0.9.3

## Correção

Ao escolher uma nova ordenação dentro de um álbum, a grade mudava, mas o visualizador continuava navegando na ordem manual antiga. Agora a sequência de deslizes usa as mesmas regras do álbum: critério, direção, agrupamento e filtros. A mídia aberta continua sendo o ponto de partida, inclusive após recriar a tela. A ordenação personalizada permanece disponível quando selecionada; o modo aleatório continua funcionando.

O novo teste reproduz a escolha pelo menu Ordenar e verifica os deslizes em ordem alfabética crescente e decrescente, com uma ordem manual antiga previamente salva. Outro caso confere os seis critérios nas duas direções, a seleção atual e o agrupamento. Esses casos passam a integrar a seleção de testes do visualizador.

## Validação de desenvolvimento

O fluxo diário agora executa os unitários, lint/build e quatro integrações essenciais, acrescentando o foco da alteração. Baterias extensas ficam para suspeitas na feature; a regressão completa continua obrigatória antes da release no GitHub. O projeto também reutiliza o AVD padrão da Galeria, preservando os dados e o isolamento dos outros emuladores.

## APK

- Arquivo: `Galeria-Android-versao-0.9.3.apk`
- Pacote: `com.galeria.android`
- Versão: `0.9.3` / código `9003`
- Certificado SHA-256: `1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce`

O APK usa a assinatura permanente para atualizar a release instalada preservando dados e preferências. A validação automatizada é feita no emulador; aparelhos físicos e codecs de fabricantes continuam sujeitos a conferência complementar.

## Validação

A execução `20261106-112130-728-c0bbcda5`, com a versão 0.9.3 definida, aprovou **250 unitários e 150 instrumentados**, sem falhas ou ignorados. Lint: zero erros e 142 avisos. Os 54 contratos das ferramentas também passaram. A regressão completa levou 654,8 s, incluindo 556,5 s no emulador Android 16/API 36. O recibo foi aceito por `assert-delivery-validation.ps1`.

Fontes compilados e do repositório conferidos: `A3CF79A71B19A29625469C504B9B011522393E039024CAA165260F3E077D1CCB`. Evidências locais em `app/build/reports/validation/20261106-112130-728-c0bbcda5/`.

## Conferência do APK otimizado

- Tamanho: 45.169.276 bytes
- SHA-256: `df4881ff0400443cc41072c1fedec9ae830e434bccc484fb16fcc3d7ad263bb8`
- Pacote, versão, certificado permanente e ausência de modo depurável conferidos.
- Build com R8 e redução de recursos; os três registradores OCR e seus construtores foram preservados.
- OCR do código otimizado aprovado em fundos claro (6,9 s) e escuro (5,4 s), com seleção/cópia e preservação do original. Uma cópia com assinatura de teste foi usada para preservar os dados do emulador: as 580 entradas internas não relacionadas à assinatura são idênticas às do APK permanente. A cópia não é distribuída.
- A instalação debug e o APK normal de instrumentação foram restaurados; o recibo de entrega foi aceito novamente. Evidências em `app/build/reports/release-0.9.3/`.
