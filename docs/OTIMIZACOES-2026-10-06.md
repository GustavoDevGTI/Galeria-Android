# Varredura de otimização — implementação dos dez pontos

Base: código da 0.8.64. Esta etapa não altera versão, dependências, assinatura ou publicação.

A publicação posterior é a versão 0.8.65 (código 8065), com os mesmos fontes funcionais e nova regressão completa aprovada. Consulte [as notas e evidências da publicação](RELEASE-0.8.65.md).

| Ponto | Mudança implementada |
| --- | --- |
| 1. Operações de arquivos na interface | Mover, copiar, ocultar, excluir, restaurar e renomear no visualizador, e mover/excluir/restaurar na seleção, executam em um trabalhador. A conclusão atualiza a tela na thread principal. Cliques concorrentes são rejeitados; uma gravação iniciada termina mesmo se a tela fechar. A navegação não troca a mídia alvo durante a operação. |
| 2. Classificação de ocultos | Classificação de `.nomedia` e caches gerados ocorre antes da entrega dos modelos, fora da interface. Olho, checkbox e listagem utilizam os indicadores já classificados. Recontagens preservam esses indicadores e as escolhas do usuário. |
| 3. Editores | Renderização, rotação, espelhamento, redimensionamento e gravação da imagem são feitos fora da interface. Apenas o estado leve de desenho é capturado na tela; os controles ficam indisponíveis durante a transformação. A duração inicial do editor de vídeo também é consultada em segundo plano. |
| 4. Trabalho repetido do catálogo/capas | A base indexada é reutilizada por até 30 segundos, somente com mesma geração do MediaStore e revisão de mutação. Sem um token de geração confiável não há essa reutilização. Pastas ocultas e regras de visibilidade continuam reconciliadas. O catálogo válido utiliza seu snapshot; a capa automática encontra o primeiro item com o mesmo comparador, em uma passagem linear, sem ordenar o álbum inteiro. |
| 5. Banco e paginação | Migração não destrutiva 2→3 acrescenta índices compostos por escopo/álbum/data e escopo/data. O resumo calcula a capa uma vez por álbum. Consultas paginadas usam predicados diretos e uma lista fechada de ordenações; só fazem o JOIN de ordem personalizada quando necessário. Valores de busca continuam parametrizados. |
| 6. Atualização de álbuns físicos | Filtro e cálculo das diferenças acontecem em segundo plano. A grade notifica somente células alteradas/removidas/inseridas. Atualizações antigas são descartadas após uma mutação, reordenação ou fechamento. A seleção continua identificada por URI. A sincronização da ordem deixou de usar buscas quadráticas. |
| 7. Decodificação duplicada de thumbnails | Grade e capas aguardam a mesma decisão persistida em vez de iniciar simultaneamente um decoder Coil para o primeiro quadro. Consultas a arquivos frios também ocorrem no trabalhador. Há fallback se a geração falhar, cancelamento por célula reciclada e descarte do carregamento anterior. Continuam 960 px e JPEG 92, sem escolher uma nova capa a cada abertura. |
| 8. Configurações e navegadores de pastas | Medição/limpeza de cache e listagem/contagem/criação de diretórios saem da thread principal. Medições simultâneas e reconstruções imediatas de configurações reutilizam o resultado. O seletor reutiliza linhas e não enumera a pasta em cada bind. Pedidos antigos não substituem a pasta atual. |
| 9. Motion Photos | Varreduras verificam relevância entre blocos e reutilizam um buffer. Resultados positivos e negativos persistem por URI/tamanho/modificação/revisão, com até 512 entradas; cancelamento não persiste um falso negativo. O cache do visualizador tem até 64 entradas. |
| 10. Manutenção de caches | Durações resolvidas usam cache de até 2048 entradas, com identidade/revisão/tamanho/data. A manutenção de miniaturas é limitada temporalmente e remove somente originais comprovadamente excluídos com acesso integral. Itens ocultos, na Lixeira, pendentes ou inacessíveis não são tratados como excluídos. Entradas antigas sem manifesto permanecem até haver evidência segura. |

## Limites deliberados

Álbuns físicos continuam com o modelo completo de itens: paginar indiscriminadamente mudaria seleção de todos, arraste contínuo, ordem personalizada e reprodução aleatória. O ponto 6 aplica a primeira medida indicada na auditoria — atualização diferencial — mantendo essas funções. Recentes/Todas as mídias continuam com a paginação existente. Não há promessa de consumo constante para um álbum físico arbitrariamente grande.

Não foi reduzida a qualidade das miniaturas ou alterada a regra de escolha de quadros. Cache persistido não é um álbum público. Limpeza por tamanho/LRU não descarta uma decisão de thumbnail ainda válida; somente a memória e os metadados têm limites. Mudar o conteúdo da mídia invalida sua versão.

## Validação

Contratos adicionados/verificados: migração 1→3 e 2→3 preservando mídia/ordem/duração; plano SQLite sem ordenação temporária na busca de capa; consulta parametrizada; atualização de 10.000 itens sem rebind global, com preservação da seleção e rejeição de atualização obsoleta; equivalência da capa em todos os modos/grupos/direções; operação concorrente/lifecycle; cancelamento e reutilização de Motion Photos; cancelamento, qualidade e estabilidade de thumbnail, preservação na Lixeira e limpeza após exclusão definitiva. A resolução de duração invalida os snapshots em tempo constante e impede a entrega de uma varredura anterior; a atualização de um único álbum após essa invalidação preserva os demais álbuns.

Testes de mover/excluir verificam a conclusão real do trabalhador antes das mesmas asserções de atualização imediata, navegação, não reaparecimento e integridade dos arquivos. O teste de OCR exige a janela pronta além da conclusão do reconhecimento. Nenhuma falha ou função foi removida para obter aprovação.

O teste de rolagem rápida aguarda notificações e geração do MediaStore estabilizarem antes de instalar seu catálogo sintético. A falha foi reproduzida na sequência capa→rolagem: uma notificação tardia do arquivo real removido pelo teste anterior provocava, corretamente, a reconciliação do app com o dispositivo (e zerava o álbum artificial). A atualização automática de produção permanece ativa; as asserções de arraste, total de itens, duração e células visíveis permanecem iguais. O diagnóstico de falha inclui quantidade, posições e visibilidade da barra.

Regressão final aprovada em 06/10/2026, execução `20261006-143806-061-33a58b54`: compilação debug e APK instrumentado concluídos, 229/229 testes unitários e 106/106 testes instrumentados no emulador Android 16/API 36, sem falhas ou testes ignorados. Lint: zero erros e 126 avisos (não se trata de lint sem avisos). A execução completa levou 629,1 segundos; os testes no emulador, 547 segundos. A aprovação confere fingerprint dos fontes e hashes dos APKs/relatórios; não é uma publicação nem valida assinatura release ou codecs de aparelhos reais.

Evidências: `app/build/reports/validation/20261006-143806-061-33a58b54/result.json`, relatório instrumentado e XMLs unitários na mesma pasta; `app/build/reports/validation/delivery.json` aponta para esta execução. Também passaram os 45 contratos sintéticos das ferramentas de validação.
