# Galeria Android 0.8.53

Esta versão adiciona uma experiência opcional para filmes e séries sem modificar a simplicidade do player normal.

## Principais mudanças

- Novo botão de modo cinema no visualizador de vídeos.
- A ativação gira a tela para a horizontal e a desativação retorna imediatamente ao modo anterior.
- A troca de modo agora possui escurecimento leve, animação suave e indicação central `Modo cinema` ou `Modo normal`.
- Gestos verticais ajustam brilho no lado esquerdo e volume no lado direito durante o modo cinema.
- O menu do player permite selecionar trilhas de áudio e legendas incorporadas.
- A opção do álbum permite usar o modo cinema em todos os seus vídeos e preservar as preferências de trilhas.
- A barra de ações do visualizador e das seleções recebeu ícones mais leves e consistentes.
- Favoritos agora são representados por coração em toda a interface.
- O botão de cinema funciona por clique e não mantém brilho ou aparência de botão pressionado.

## Validação

- 67 testes unitários aprovados.
- Testes específicos do modo cinema, reprodução, recriação da Activity e rotação aprovados no Android 16/API 36.
- A execução integral dos 43 testes instrumentados foi interrompida após 25 casos por uma reinicialização do sistema do emulador; os dois casos afetados passaram quando repetidos isoladamente após a reinicialização.
- Lint e builds debug aprovados.
- Build release assinada e otimizada com R8.

O APK anexado mantém a mesma assinatura permanente das versões anteriores. Não houve mudança no modelo de permissões nem publicação automática na Play Store.

Limite conhecido: seleção de áudio e legenda depende das trilhas realmente incorporadas e dos codecs suportados pelo Android do aparelho.
