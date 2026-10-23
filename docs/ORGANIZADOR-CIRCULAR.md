# Organizador circular

Esta etapa faz parte da versão **0.9**, código Android **9000**. A release usa a tag `v0.9` e o nome `Galeria-Android-versao-0.9.apk`. As [notas da release](RELEASE-0.9.md) registram a validação completa e a assinatura do APK.

O atalho **Organizar** ocupa o antigo lugar de Filtrar na barra de navegação da galeria e do álbum. Filtrar permanece no menu de três pontos.

## Uso

1. Selecione um álbum físico de origem.
2. Selecione de **1 a 8 destinos diferentes** e toque em Começar a organizar. O círculo se divide igualmente conforme a quantidade escolhida; com um destino, ele ocupa todo o anel. A ordem de seleção define suas posições, começando pelo topo e seguindo no sentido horário. A quantidade e a ordem da última configuração da origem são lembradas. É possível criar pastas vazias nessa etapa.
3. Empurre a mídia central ou arraste até um destino. Segure qualquer setor e arraste sobre outro para trocar suas posições.

A reserva fica acima do círculo. Arrastar até ela guarda a mídia na sessão, sem mover o arquivo. Seu botão mostra a última mídia reservada e se transforma no próprio carrossel. Arrastar uma miniatura para um destino não consome a fila central. Uma miniatura selecionada pode ser arrastada em qualquer direção; as demais permitem rolagem horizontal e arrasto vertical/diagonal. Os menus de toque também oferecem envio e troca de posições sem depender de gestos.

O destino mais recente mantém o destaque de contraste do tema. Preto usa branco e branco usa preto. A entrada da mídia provoca um deslocamento radial curto do setor. A mídia faz uma animação de voo enquanto a seguinte já está disponível no centro.

## Estrutura e integridade

- `OrganizerRules` concentra setores, limiares de gesto, seleção, troca e fila recuperável, com testes JVM.
- `OrganizerController` possui fila, reserva, histórico e movimentos serializados fora da thread da interface. Sobrevive à rotação; reabrir durante um movimento reutiliza a sessão em andamento.
- `OrganizerMediaMover` devolve a identidade real do arquivo após o movimento, incluindo renomeação por colisão. Desfazer usa essa identidade. O caminho é validado dentro do armazenamento e o fallback direto usa renomeação atômica, sem sobrescrever arquivos.
- `OrganizerBoard` contém desenho, gestos, animações, doze miniaturas antecipadas e três posições visíveis da pilha. O carrossel usa RecyclerView e carrega apenas as miniaturas necessárias. Não há temporizador para liberar a próxima mídia.
- `OrganizerActivity` cuida da seleção e das permissões. No Android 11 ou superior, o acesso de gerenciamento de arquivos é solicitado antes de começar, para permitir movimentos contínuos.

O arquivo só sai fisicamente da origem quando a operação termina com sucesso. Falhas devolvem a mídia à sua posição na fila/reserva. A reserva e a ordem dos destinos são persistidas; o histórico de desfazer pertence à sessão. Após morte do processo, as mídias disponíveis são consultadas novamente na origem, sem repetir automaticamente movimentos antigos.

## Validação

### Entrega completa — 23/10/2026

A execução `20261023-101933-566-07f56200` aprovou **250 unitários e 140 instrumentados**, sem falhas ou ignorados; lint sem erros, 132 avisos. Inclui os 11 casos do organizador e todos os recursos anteriores. O OCR do APK otimizado também passou em fundos claro e escuro. A assinatura permanente e a identidade da versão foram conferidas. As notas da release registram os hashes, os relatórios e as tentativas anteriores rejeitadas.

### Destinos personalizáveis — 23/10/2026

A seleção permite de 1 a 8 destinos, com o botão Começar habilitado a partir do primeiro. O anel, os rótulos, a detecção de direção e os menus usam a quantidade selecionada; não existem setores vazios para os destinos não escolhidos. A configuração salva mantém quantidade e ordem. O limite de oito e a exclusão da origem continuam obrigatórios.

Passaram **250 testes unitários e 11 instrumentados do organizador**, sem falhas/ignorados; lint sem erros, 132 avisos. O teste de quantidades percorre 1 a 8 destinos com envio real, desfazer, troca por gesto, rotação e reabertura. Foram conferidas capturas com 1, 3 e 8 destinos. Essa validação é focada no organizador; não representa uma nova aprovação completa de release.

Fontes compilados conferidos com o repositório: `9ED081212EAC6390D682C94A811CC0FE442B041FF291BB487ED432EF02B087F6`. APK debug 0.9 / código 9000, SHA-256 `F7C14981E2C7123B796F346D047E128F9F9AC11EED4A0223B97845ED52B4CE4C`. Logs locais: `organizer-variable-build.txt`, `organizer-variable-native.txt` e `app/build/reports/organizer-variable/` na cópia de compilação.

`OrganizerRulesTest` cobre gestos e seleção para todas as quantidades de 1 a 8 destinos, troca de posições, fila imediata, reserva e recuperação ordenada de falhas. `OrganizerInstrumentedTest` verifica seleção, arquivos reais, colisões, desfazer, rotação, orientação horizontal, carrossel, reordenação persistente, contraste e movimentos bloqueados/falhos. Também verifica cada quantidade de destinos com envio por gesto, troca de posições e reabertura. A classe instrumentada está incluída na área `Albums` do plano de validação; a barra e o filtro continuam cobertos por `GalleryBrowseBarInstrumentedTest` na área `Appearance`.

### Resultado em 22/10/2026

A validação final por área `Albums`, execução `20261022-170400-730-5f817de9`, aprovou **249 unitários e 38 instrumentados**, sem falhas/ignorados. Lint: zero erros e 131 avisos. Os 45 contratos das ferramentas também passaram. A impressão digital dos fontes compilados e do repositório foi conferida: `080BB2B905E0EEEA70C758C9481F1E254A94EC31F9391ABC6B95BE01135957D8`.

APK debug 0.9 / código 9000, SHA-256 `BB32C95665FD0054ADC7E18B91E350B56B79B83D334E4647A4EFE9A7DA8BA058`. Essa aprovação é por área, não uma aprovação completa de publicação; não houve push, assinatura release nem publicação nesta etapa.

As tentativas anteriores permanecem nos relatórios locais. Elas revelaram conflito de interceptação no carrossel, sincronização de foco/layout nos testes de capa e rolagem e um nome artificial na fixture de contagem de ocultos. Esses casos foram corrigidos sem remover suas verificações. Uma rodada diagnóstica passou 29 testes, mas foi rejeitada pelo verificador porque a contagem esperada foi informada como 20; ela não foi usada como aprovação final. Execuções interrompidas também não foram aproveitadas como aprovação.
