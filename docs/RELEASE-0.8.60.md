# Galeria Android 0.8.60

## Novidades e correções

- Linha do tempo visual em vídeos, Motion Photos e prévia de corte, com miniaturas progressivas, cache privado, toque/arraste para navegar e ajuste fino em vídeos longos.
- Correção do encaminhamento de gestos sobre vídeos para permitir navegar entre mídias.
- Correção do envio à Lixeira e restauração de arquivos acessados diretamente, incluindo mídias ocultas; arquivos já enviados à Lixeira não devem voltar pela varredura de pastas.
- Editor com tema preto, ferramentas por ícones e submenu centralizado com margens; título da mídia abreviado e olho de visualização no canto inferior direito da seleção.
- Recorte por cantos/bordas, proporções e espelhamento; texto sobre a imagem, pincel com espessuras/tipos e espectro de cores. Redimensionamento preservado como ferramenta específica.
- Ícone automático de texto reconhecido, OCR por toque prolongado e acesso ao OCR dentro do editor, com leitura detalhada de documentos.

## Validação e limites

Esta publicação foi solicitada para testes manuais no aparelho. **A validação completa da interface da versão final ainda está pendente.**

- A última suíte instrumentada completa, anterior aos ajustes finais, teve 71 aprovações e 4 falhas. Os ajustes foram implementados, mas a repetição final foi interrompida por falhas nativas/instabilidade do emulador; não há aprovação integral pós-correções.
- O celular físico ainda não ficou acessível por USB. Não foram concluídos testes automatizados nele.
- Compilação release assinada aprovada; 90 testes unitários aprovados; lint sem erros, com 119 avisos. Nenhuma chave ou senha incluída no repositório.
- Nenhuma publicação na Play Store. Os arquivos originais devem ser preservados ao salvar cópias pelo editor; validar primeiro com arquivos sem importância.

## Testes manuais

[Roteiro completo com passos e resultados esperados](https://github.com/GustavoDevGTI/Galeria-Android/blob/v0.8.60/docs/TESTES-MANUAIS-0.8.60.md).

Prioridades: navegação entre vídeos; excluir/restaurar; linha do tempo; corte; texto/pincel; OCR; cinema e trilhas; Motion Photos; seleção; isolamento de ocultos.

Instale por cima da versão release anterior para manter as preferências. Se houver conflito de assinatura com uma instalação debug, não desinstale antes de preservar os dados necessários.
