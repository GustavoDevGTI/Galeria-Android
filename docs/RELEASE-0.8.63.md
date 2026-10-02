# Galeria Android 0.8.63

## Mudanças

- Mover todas as mídias de um álbum abre o destino e encerra a origem, mesmo quando permanecem documentos/subpastas. Excluir o último item volta à lista de álbuns; busca/filtro vazio não decide se a pasta realmente ficou vazia.
- Configuração essencial “Usar lixeira”, ativada por padrão. Desativada, as próximas exclusões são definitivas, com aviso correspondente. Os itens já na lixeira continuam recuperáveis.
- Contagem atualizada no painel de ocultos, inclusive com o painel aberto, preservando checkbox, pin e exposição temporária. Não revela pastas novas nem gera thumbnails para contar arquivos.
- Atalho de visualizar na seleção usa um ícone de ampliar, na posição inferior direita, mantendo a seleção ao voltar. Ícone de girar agora representa uma imagem com seta de quarto de volta, diferente de atualizar.
- Controladores isolados para transições, gestos, reprodução, cinema, Motion Photos e propriedade dos pedidos de OCR, preservando as funcionalidades existentes.
- Validação por área, portão local de entrega, evidências com hashes e melhorias de sincronização/fixtures dos testes. Três verificações puras foram migradas do emulador para unitários, sem remover sua cobertura.

## Validação

A validação final da versão 0.8.63 passou em **227/227 unitários e 92/92 instrumentados** no AVD dedicado API 36, sem falhas/ignorados e com lint sem erros (126 avisos existentes). Também passaram 45 contratos das ferramentas de validação, separados dos testes do aplicativo. Execução local: `20261002-164636-663-2c86e163`; etapa instrumentada de 376,2 segundos.

Uma tentativa anterior foi interrompida porque o snapshot do emulador restaurou uma janela ANR do SystemUI, interceptando os toques. A execução completa aprovada ocorreu após inicialização limpa, sem reutilizar esse snapshot. O executor agora rejeita uma janela ANR já ativa antes da instalação; não ignora falhas nem descarta testes.

O APK release otimizado foi compilado com sucesso. Conferidos: pacote `com.galeria.android`, versão `0.8.63`, código `8063`, ausência da Activity exclusiva de testes e assinatura válida com o mesmo certificado da 0.8.62.

- Arquivo: `Galeria-Android-versao-0.8.63.apk`
- Tamanho: 45.085.399 bytes
- SHA-256: `14186c7b065651117f52dde0fe652a3847ba592212e4eb40a962df8bad2c7196`
- Certificado SHA-256: `1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce`

Os testes automatizados de interface usam debug. Não substituem a confirmação do APK release otimizado em aparelhos físicos, codecs/trilhas e variantes de Motion Photos de fabricantes. Nenhuma chave ou credencial será adicionada ao Git e não há publicação na Play Store.

## Atualização

Atualize por cima da release anterior para preservar dados e preferências. Não desinstale a instalação existente por um conflito de assinatura sem preservar seus dados. Releases e APKs históricos são mantidos intactos.
