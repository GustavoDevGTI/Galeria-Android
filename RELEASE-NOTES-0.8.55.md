# Galeria Android 0.8.55

Esta versão adiciona coleções virtuais e uma Lixeira, preservando as mídias nas pastas originais até que sejam excluídas.

## Principais mudanças

- Recentes reúne fotos e vídeos em ordem cronológica sem duplicar arquivos.
- Favoritos mostra automaticamente as mídias marcadas com coração, sem movê-las.
- Lixeira permite restaurar ou excluir definitivamente os itens. A exclusão comum agora envia primeiro para a Lixeira.
- Câmera, Capturas de tela, Favoritos, Downloads e Recentes ficam no topo da listagem; os demais álbuns continuam seguindo a ordenação escolhida.
- O modo cinema, suas preferências de áudio e legendas e a reprodução aleatória continuam disponíveis. Vídeos abertos por Recentes ou Favoritos agora consultam as preferências de cinema da pasta física de origem.
- Corrigida uma carga agendada que podia causar falha ao fechar uma pasta rapidamente.

## Comportamento e limites

- A opção de modo cinema para todos os vídeos continua configurada em pastas físicas; as coleções virtuais não guardam uma configuração própria.
- As opções de trilha dependem das faixas realmente presentes no vídeo e dos formatos suportados pelo Android.
- No Android 11 ou superior, a Lixeira usa o MediaStore do sistema. Em versões anteriores, usa uma área local de recuperação; não há prazo de limpeza automática nessa área.
- A configuração antiga que alternava entre excluir diretamente e mover para a Lixeira foi removida: a exclusão comum sempre envia à Lixeira; a exclusão definitiva fica dentro dela.
- Nenhuma permissão nova foi adicionada. O APK continua assinado com a mesma chave das versões anteriores e não há publicação na Play Store.

## Validação

- 72 testes unitários aprovados; lint debug aprovado.
- Sete testes instrumentados aprovados no Android 16/API 36, cobrindo modo cinema, preferência da pasta física ao abrir por Recentes, reprodução aleatória, coleções virtuais e ações da Lixeira.
- Interface verificada no emulador: reprodução aleatória avança de mídia; o menu cinema abre as opções de áudio e legenda; uma legenda incorporada pode ser selecionada e permanece marcada ao reabrir o seletor.
- APK release otimizado e assinado; certificado conferido com o da versão 0.8.54.

Não foi validada a troca entre duas faixas de áudio reais no mesmo arquivo, pois a mídia de teste disponível tinha somente uma faixa de áudio.
