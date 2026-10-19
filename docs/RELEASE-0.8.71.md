# Galeria Android 0.8.71

## Alterações

- Corrigido o toque prolongado que não iniciava o OCR depois de carregar o álbum. A mesma foto podia passar a usar outra URI do MediaStore; agora o gesto identifica a mídia e usa sua referência atual. O detector de toque é compartilhado com a imagem, e um pequeno movimento após segurar não inicia uma troca de mídia.
- Mantido o reconhecimento local e a seleção sobre a própria foto, com testes em fundos branco e preto, inclusive com zoom. O original não é alterado.
- Na barra inferior da lista de álbuns permanece **Ocultos**. Dentro das pastas, o terceiro atalho passa a ser **Aleatório**, com ícone de embaralhar, usando a reprodução aleatória já existente. A Lixeira mantém a restrição de reprodução aleatória de itens excluídos.
- No menu de ocultos, **OK** fica centralizado em uma barra mais compacta, inteira clicável, com animação de clique e bloqueio de confirmações repetidas. A contagem de mídias fica menor que o nome do álbum.
- A contagem acompanha inclusões, exclusões e movimentações enquanto o menu está aberto, inclusive alterações diretas em pastas com `.nomedia`. A observação se limita às pastas ocultas conhecidas, termina ao fechar o menu e não recarrega a galeria inteira. Arquivos vazios não entram na contagem; álbuns vazios saem da lista. As escolhas de ocultação, fixação e revelação temporária são preservadas.
- No seletor de tema, o cartão mostra o fundo principal da galeria e **Prévia do tema** mostra a cor secundária real dos submenus. Cores e textos acompanham a escolha em tempo real, mantendo os comportamentos de Aplicar e Cancelar.

Sem novas dependências, mudanças no banco de dados ou retirada de ferramentas do visualizador/editor.

## Validação e APK

Com a versão `0.8.71` / código `8071` definidos, a validação completa de entrega `20261019-154102-315-7e5c5714` aprovou **239/239 testes unitários e 126/126 instrumentados**, sem falhas ou ignorados, no emulador dedicado Android 16/API 36. Lint: zero erros e 129 avisos. Os 45 contratos das ferramentas de validação também passaram.

A execução durou 711,3 segundos, incluindo 625,6 segundos de instrumentação. Foram exercitados os recursos anteriores de cinema/trilhas, reprodução, navegação, edição, timeline, seleção, catálogo, ocultos e álbuns virtuais, além das mudanças desta versão. Evidências locais em `app/build/reports/validation/20261019-154102-315-7e5c5714/` e `delivery.json`; o portão confere os fontes, os APKs e os relatórios aprovados.

O smoke do **código otimizado** passou nos fundos claro (13,6 segundos) e preto (9,7 segundos): toque prolongado com pequeno arraste, reconhecimento, seleção, cópia e preservação da foto original. Foi utilizada uma cópia com assinatura de teste para preservar a instalação do emulador; seus 28 arquivos DEX, bibliotecas nativas e assets conferidos são idênticos aos do APK permanente. A cópia diagnóstica não é distribuída. O R8 preservou os três registradores OCR e seus construtores, conferidos pela tarefa da release.

- Arquivo: `Galeria-Android-versao-0.8.71.apk`
- Pacote: `com.galeria.android`
- Versão: `0.8.71` / código `8071`
- Tamanho: 45.125.341 bytes
- SHA-256: `ddd64a21256d64fe8a23d72ef7f07310df617c3f100fa6da60ee8113dbe27172`
- Certificado SHA-256: `1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce`

Conferidos assinatura, certificado permanente, pacote/versão e ausência de modo depurável. Evidências locais em `app/build/reports/release-0.8.71/`.

## Atualização

O APK usa a chave permanente das versões anteriores. Atualize por cima da release instalada para preservar mídias, preferências e organização; não é necessário reinstalar do zero. Nenhuma chave ou credencial é incluída no repositório.

Os testes no emulador não substituem a confirmação complementar em aparelhos físicos, codecs de fabricantes e Motion Photos reais. Nenhuma publicação automática na Play Store é realizada.
