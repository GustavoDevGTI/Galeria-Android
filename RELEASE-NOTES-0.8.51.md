# Galeria Android 0.8.51

Esta versão reúne a atualização técnica e as correções validadas após a 0.8.50.

## Principais mudanças

- Atualização compatível de Kotlin, Android Gradle Plugin, Coil, Room, Paging e AndroidX Test.
- Separação de responsabilidades de catálogo, seleção, fila, reprodução, metadados, permissões e ações de mídia, preservando a interface existente.
- Atualização imediata da pasta após exclusão ou movimentação confirmada.
- Abertura automática da pasta de destino quando a movimentação esvazia a origem.
- Retorno do gerenciador de pastas ocultas, mensagens e confirmações ao centro; menus de opções, ordenação, criação de pasta e seleção de destino permanecem laterais.
- Correção do cache após renomear ou mover arquivos e da barra de tempo do vídeo após retornar ao app.
- CI com testes, lint, APK debug e relatórios temporários.
- Checksum do Gradle Wrapper e proteção adicional contra versionamento acidental de chaves.

## Validação

- 55 testes unitários: aprovados.
- 34 testes instrumentados no Android 16/API 36: aprovados.
- 4 testes de desempenho/perfil: aprovados no emulador.
- Lint: 0 erros; 90 avisos não suprimidos.
- Builds debug e release com R8: aprovadas.

O APK anexado é assinado com a mesma chave permanente da 0.8.50. Não houve mudança no modelo de permissões nem publicação automática na Play Store.

Limite conhecido: os testes no emulador não substituem a validação em aparelho físico, especialmente para cartões SD, bibliotecas extensas, codecs variados e acesso parcial a mídias.
