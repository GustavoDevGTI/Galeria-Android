# Atalho de velocidade no vídeo

- Toque curto: 1× → 1,5× → 2× → 1×. Se 0,5× foi escolhido no menu, o próximo toque retorna a 1×.
- Toque prolongado: abre o seletor existente (0,5×, 1×, 1,5× e 2×), sem executar o ciclo de toque curto.
- Mantidos layout, player e persistência na recriação da tela. O botão informa imediatamente a velocidade, utiliza o feedback de toque padrão e possui descrição acessível dos dois gestos. Não altera o estado de reprodução/pausa.

Validação por áreas Playback e Cinema: `20261013-145051-675-fdc26c0b`, 237 unitários e 17 testes de interface aprovados no emulador API 36, sem falhas/ignorados; lint sem erros, com 129 avisos. O novo teste usa um vídeo real e verifica repetição do ciclo, velocidade efetiva no mesmo player, ausência de play involuntário, abertura por toque prolongado sem ciclo, presença das quatro opções e seleção de 0,5×. A validação inteira levou 223,7 segundos, sendo 104,8 segundos de instrumentação.

Não houve incremento de versão, commit, push ou release nesta alteração. Esta aprovação por áreas não substitui a regressão completa exigida antes de publicar.

Após o pedido posterior de publicação, esta alteração foi incluída na 0.8.69 (8069), junto da barra inferior. A regressão completa final aprovou 237 unitários e 124 testes instrumentados, sem falhas/ignorados e com lint sem erros (129 avisos), após a correção adicional de ciclo de vida do indicador de carregamento. Veja [as notas da release](RELEASE-0.8.69.md).
