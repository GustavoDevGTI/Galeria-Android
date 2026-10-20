# Galeria Android 0.8.72

## Alterações

- Recentes permanece em primeiro lugar. Depois vêm os álbuns fixados pelo usuário e, em seguida, os essenciais, ordenados pela atividade mais recente: Câmera, Capturas de tela, Favoritos e Downloads. Os demais continuam respeitando a ordenação escolhida.
- Favoritar uma foto antiga destaca Favoritos pela data da nova marcação, sem alterar a data ou a localização do arquivo.
- Quando existem várias pastas de capturas, somente a mais recentemente utilizada recebe prioridade de álbum essencial. O sistema usa a data da captura, nomes padronizados e metadados disponíveis para reduzir a confusão com arquivos importados; a pasta legada continua acessível, sem ocultação ou exclusão. Sem metadados suficientes, a identificação utiliza a melhor evidência disponível, não uma identificação garantida do aparelho de origem.
- O menu de três pontos da tela inicial inclui **Espaçamento entre álbuns**, com prévia imediata e preferência salva. O ajuste não modifica o espaçamento da grade dentro das pastas e não refaz a busca do catálogo.
- O visualizador passa a usar cinco posições iguais, somente com ícones: **Editar, Texto, Informações, Compartilhar e Excluir** para fotos; **Editar, Som, Cinema, Compartilhar e Excluir** para vídeos. Compartilhar e Excluir ficam nas mesmas posições nos dois tipos de mídia.
- O atalho Texto permanece disponível nas fotos mesmo antes da detecção automática. O reconhecimento e a seleção sobre a imagem, inclusive por toque prolongado, continuam usando o mecanismo existente.
- Motion Photo passa a ter um botão sobre a área da imagem, fora da barra de ações. Ele acompanha a exibição/ocultação dos controles e mantém a reprodução e o retorno à mesma foto.
- Favorito continua no topo. Cortar, Girar e Edição personalizada permanecem dentro de Editar. Nenhuma ferramenta do visualizador ou editor foi removida.

Sem novas dependências ou alterações no esquema do banco de dados.

## Validação e APK

Com a versão `0.8.72` / código `8072` definidos, a validação completa de entrega `20261020-160812-213-a3c57aba` aprovou **241/241 testes unitários e 129/129 instrumentados**, sem falhas ou ignorados, no emulador dedicado Android 16/API 36. Lint: zero erros e 130 avisos. Os 45 contratos das ferramentas de validação também passaram.

A execução aprovada durou 1.066,6 segundos, incluindo 1.007,5 segundos de instrumentação. A suíte completa inclui os recursos anteriores e os novos casos de ordenação por atividade, espaçamento, alinhamento das ações, OCR, Motion Photo, edição, cinema/trilhas e operações de arquivos. Evidências locais em `app/build/reports/validation/20261020-160812-213-a3c57aba/` e `delivery.json`.

O smoke do **código otimizado** passou em fundo claro (18 segundos) e preto (14,5 segundos), reconhecendo e copiando texto sobre a imagem pelo toque prolongado e preservando o original. Foi utilizada uma cópia com assinatura de teste para manter a instalação do emulador; seus 28 arquivos DEX, bibliotecas nativas e assets conferidos são idênticos aos do APK permanente. A cópia diagnóstica não é distribuída. A montagem também confirmou os três registradores OCR e seus construtores preservados pelo R8.

- Arquivo: `Galeria-Android-versao-0.8.72.apk`
- Pacote: `com.galeria.android`
- Versão: `0.8.72` / código `8072`
- Tamanho: 45.143.752 bytes
- SHA-256: `744ff2b05c7abe8b7e4e3ef898c83e91212582c759af64c88d8ec41012b43ce5`
- Certificado SHA-256: `1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce`

Conferidos assinatura, certificado permanente, pacote/versão e ausência de modo depurável. Evidências locais em `app/build/reports/release-0.8.72/`.

### Tentativas rejeitadas e correções dos testes

- A execução `20261020-153514-309-82cc0229` terminou com 128/129 instrumentados aprovados. A única falha ocorreu no `@After` do teste de excluir a última mídia: a reconstrução do catálogo começou antes do encerramento da tela redirecionada e das notificações assíncronas do MediaStore. A limpeza passou a aguardar a destruição das telas encerradas e uma janela de estabilidade do token/revisão, com timeout explícito. O teste passou duas vezes em seguida. Não há captura/omissão da exceção nem mudança no comportamento de exclusão do app.
- A execução `20261020-155319-795-d2776d3c` terminou com 128/129 aprovados. O teste de abrir Cortar consultou a raiz antiga e invisível de `DetailActivity`, embora `ImageEditActivity` já estivesse ativa. O helper passou a aguardar o editor com foco e verificar o texto nessa janela específica. O mesmo teste passou duas vezes em seguida. O código do editor não foi modificado.
- Essas execuções são históricas e rejeitadas; testes direcionados não substituem a aprovação completa exigida para a publicação. Nenhum teste foi removido ou ignorado.

## Atualização

O APK utiliza a chave permanente das versões recentes. Atualize por cima da release instalada para preservar mídias e preferências; não é necessário reinstalar do zero. Nenhuma chave ou credencial é incluída no repositório.

Os testes no emulador não substituem a confirmação complementar em aparelhos físicos, codecs de fabricantes e Motion Photos reais. Não há publicação automática na Play Store.
