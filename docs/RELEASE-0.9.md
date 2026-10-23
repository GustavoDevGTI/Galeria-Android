# Galeria Android 0.9

## Alterações

- Novo organizador circular: escolha um álbum de origem e de **1 a 8 destinos**. O anel distribui os destinos conforme a quantidade selecionada.
- Envie mídias arrastando ou empurrando na direção do álbum. A próxima mídia aparece imediatamente, com miniaturas antecipadas e uma pilha visível no centro; os arquivos são movidos em segundo plano.
- A mídia faz uma animação de voo e o destino se desloca levemente para fora ao receber. O último destino conserva o destaque de contraste do tema, incluindo branco sobre preto e preto sobre branco.
- Segure um destino e arraste sobre outro para trocar as posições. Quantidade e ordem são lembradas para cada origem.
- O botão Reservar mostra a mídia mais recente e se transforma em um carrossel no mesmo lugar. Distribuir uma mídia reservada não consome a fila central.
- Desfazer acompanha a identidade real do arquivo após movimentação e renomeação por colisão. Falhas devolvem as mídias à fila ou à reserva, preservando sua ordem.
- O atalho Organizar ocupa o lugar de Filtrar na barra da galeria e dos álbuns. Filtrar permanece no menu de três pontos.
- Layout adaptado para orientação vertical e horizontal, preservando o estado ao girar a tela.

Sem novas dependências ou alterações no esquema do banco de dados. O organizador trabalha com pastas físicas do armazenamento interno; os destinos não incluem a própria origem nem coleções virtuais. No Android 11 ou superior, solicita gerenciamento de arquivos antes de iniciar a organização contínua.

## Validação e APK

Com versão `0.9` / código `9000` definidos, a validação completa de entrega `20261023-101933-566-07f56200` aprovou **250/250 testes unitários e 140/140 instrumentados**, sem falhas ou ignorados, no emulador dedicado Android 16/API 36. Lint: zero erros e 132 avisos. Os 45 contratos das ferramentas também passaram. A execução durou 693,5 s, incluindo 640,9 s de instrumentação.

Os fontes compilados e os do repositório foram conferidos: `B1C3F08C61F4DA34DBD939487D36ACBEEC02BCA79DB9C44DC3392081F01A0749`. Evidências locais em `app/build/reports/validation/20261023-101933-566-07f56200/` e `delivery.json`.

O smoke de OCR do **código otimizado** passou em fundo claro (8,1 s) e escuro (6,2 s), reconhecendo, selecionando e copiando texto sem modificar o original. Foi utilizada uma cópia com assinatura de teste para preservar a instalação do emulador: seus **580 arquivos internos não relacionados à assinatura**, incluindo o DEX, recursos, bibliotecas e assets, foram conferidos como idênticos aos do APK permanente. A cópia diagnóstica não é distribuída. O APK normal de instrumentação foi restaurado byte a byte e o recibo de entrega foi validado novamente após os dois smokes. Evidências em `app/build/reports/release-0.9/`.

Na build otimizada, o toque no atalho Organizar da tela inicial também abriu o seletor de álbum de origem, com `OrganizerActivity` ativa.

O APK foi compilado com R8 e redução de recursos. A montagem confirmou os três registradores OCR e seus construtores preservados. Conferidos assinatura, certificado permanente, pacote/versão e ausência de modo depurável.

- Arquivo: `Galeria-Android-versao-0.9.apk`
- Pacote: `com.galeria.android`
- Versão: `0.9` / código `9000`
- Tamanho: 45.169.171 bytes
- SHA-256: `41f9993778734e3d1f107c2ba3abd28d002a759001979c4bf4349edc2cc73a26`
- Certificado SHA-256: `1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce`

### Tentativas rejeitadas

As execuções completas `20261023-094553-063-4337c10a` e `20261023-095831-768-2f63caea` aprovaram 250 unitários e 139/140 instrumentados cada, mas foram rejeitadas pela falha de abertura da seleção no teste `viewerDetectsTextAutomaticallyAndCopiesItFromTheIcon`. O OCR havia detectado o texto; após o toque, a seleção esperada não estava presente. A barreira existente do dispatcher de entrada do Android não resolveu a falha na segunda execução. As verificações de seleção e cópia permanecem obrigatórias, sem repetir o toque nem ignorar exceções. A publicação exige uma nova execução completa aprovada após o diagnóstico.

O diagnóstico isolado recebeu o toque e abriu a seleção; a sequência da classe reproduziu a falha sem entregar eventos ao botão. O dump real do dispatcher mostrou a janela confiável `ClipboardOverlay` sobre a posição do ícone, deixada pela cópia no teste anterior. A correção fica em `androidTest`: aguarda a remoção real dessa prévia antes dos dois toques no ícone, com falha explícita se ela não desaparecer em 10 s. Não limpa a área de transferência, não desativa a proteção de entrada e não altera o código do app. Os listeners e logs temporários de diagnóstico foram removidos. Uma rodada diagnóstica também foi rejeitada por consultar o estado de ActivityScenario na thread principal; ela não representa uma aprovação de entrega.

A classe completa de OCR passou com a correção: **6/6 testes**, sem falhas ou ignorados, em 65,1 s (`app/build/reports/ocr-preview-fix/`). Essa execução focada confirma a reprodução corrigida e não substitui a validação completa da publicação.

## Atualização

O APK utiliza a chave permanente das versões recentes para permitir atualizar por cima da release instalada, preservando mídias e preferências. O histórico de desfazer pertence à sessão; a configuração dos destinos e a reserva são persistidas.

A validação automatizada foi feita no emulador; aparelhos físicos, codecs e Motion Photos de fabricantes continuam exigindo conferência complementar.
