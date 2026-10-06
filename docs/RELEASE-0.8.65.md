# Galeria Android 0.8.65

## Mudanças

- Operações de mover, copiar, ocultar, excluir, restaurar e renomear deixam de bloquear a interface. Cliques concorrentes são rejeitados e uma gravação iniciada termina mesmo se a tela fechar.
- Classificação de pastas ocultas, recontagem, navegação/criação de diretórios e medição/limpeza de cache executam em segundo plano.
- Renderização e transformações no editor de imagens e consulta inicial de duração no editor de vídeos saem da interface.
- Grade atualizada por diferenças, sem reconstruir células inalteradas; seleção e ordem personalizada são preservadas. Atualizações antigas não ressuscitam mídias removidas.
- Catálogo e base indexada são reaproveitados com controle de geração/revisão. A escolha automática da capa mantém a regra existente sem ordenar o álbum inteiro.
- Banco recebe índices compostos em migração não destrutiva 2→3. Consultas de capas e paginação evitam trabalho repetido, com buscas parametrizadas.
- Miniaturas de vídeos reutilizam uma única geração persistida, sem iniciar decodificações duplicadas. Mantidos 960 px/JPEG 92 e a decisão de thumbnail enquanto a mídia não mudar.
- Motion Photos têm detecção cancelável e cache persistente limitado. Durações resolvidas invalidam o catálogo em memória sem varrer a lista inteira.
- Limpeza de thumbnails remove apenas originais comprovadamente excluídos, preservando itens ocultos, na Lixeira, pendentes e inacessíveis.
- Testes de operações assíncronas e fixtures foram sincronizados com a conclusão real do trabalho e com notificações do MediaStore, sem desativar atualizações automáticas ou remover verificações.

Nenhuma funcionalidade foi intencionalmente removida. Álbuns físicos mantêm o modelo completo de seleção e ordem; foi aplicada atualização diferencial, não paginação indiscriminada. Nenhuma nova dependência foi acrescentada.

O detalhamento dos dez pontos e das decisões está em [OTIMIZACOES-2026-10-06.md](https://github.com/GustavoDevGTI/Galeria-Android/blob/v0.8.65/docs/OTIMIZACOES-2026-10-06.md).

## Validação

A validação final, com versão `0.8.65` e código `8065` definidos, passou em **229/229 unitários e 106/106 instrumentados** no AVD dedicado Android 16/API 36, sem falhas ou testes ignorados. Lint: zero erros e 126 avisos. Também passaram os 45 contratos das ferramentas de validação.

Execução `20261006-151513-444-65b01c44`: 436 segundos de validação, sendo 403,3 segundos de instrumentação. O build release assinado e otimizado também passou. Uma tentativa anterior não iniciou a instrumentação porque o emulador estava fechado; o AVD foi iniciado sem snapshot e a regressão completa foi executada novamente.

APK conferido: pacote `com.galeria.android`, versão `0.8.65`, código `8065`, sem a Activity exclusiva dos testes e com assinatura válida usando o mesmo certificado da 0.8.64.

- Arquivo: `Galeria-Android-versao-0.8.65.apk`
- Tamanho: 45.101.777 bytes
- SHA-256: `4286754a407c63508284061b2751dba4f5e3ee958048f790894e2bdf186e64cf`
- Certificado SHA-256: `1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce`

Os testes de interface usam debug e não substituem a confirmação do APK release otimizado em aparelhos físicos, codecs/trilhas e variantes de Motion Photos de fabricantes.

## Atualização

Atualize por cima da release anterior para preservar seus dados e preferências. Não desinstale uma instalação existente por conflito de assinatura sem preservar seus dados. Nenhuma chave/credencial é adicionada ao Git e não há publicação na Play Store. Releases e APKs históricos permanecem intactos.
