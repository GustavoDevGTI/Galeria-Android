# Galeria Android 0.8.52

Esta versão melhora a reprodução de vídeos e a personalização visual, preservando os fluxos de gerenciamento de mídia da versão anterior.

## Principais mudanças

- A última posição de um vídeo agora é lembrada por no máximo 12 horas.
- Vídeos concluídos voltam para o início e não ficam mais travados no quadro final ao serem reabertos.
- O tema também controla o contraste dos ícones das barras de status e navegação do Android.
- O seletor de tema foi substituído por um círculo de cores com ajuste de luminosidade e prévia em tempo real.
- As cores aplicadas foram suavizadas para oferecer tons mais neutros e confortáveis.
- O botão OK fica à direita nos submenus centralizados e continua à esquerda nos painéis laterais.

## Validação

- 59 testes unitários aprovados.
- 39 testes instrumentados executados no Android 16/API 36; um encerramento de Activity oscilou na execução completa e passou na repetição isolada.
- Testes específicos de memória de vídeo, personalização do tema e alinhamento dos submenus aprovados.
- Lint e builds debug aprovados.
- Build release assinada e otimizada com R8.

O APK anexado é assinado com a mesma chave permanente das versões 0.8.50 e 0.8.51. Não houve mudança no modelo de permissões nem publicação automática na Play Store.

Limite conhecido: os testes no emulador não substituem a validação em aparelho físico, especialmente para codecs variados, personalizações do fabricante e diferentes configurações das barras do sistema.
