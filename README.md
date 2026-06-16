# Galeria Android

App de galeria Android nativo, totalmente em Kotlin, com foco em desempenho, navegação fluida e gestão real de mídias e pastas do aparelho.

## Stack atual

- Kotlin nativo
- Android SDK 36
- RecyclerView para grades e listas
- MediaStore para leitura de mídias do aparelho
- AndroidX Media3 / ExoPlayer para reprodução de vídeo

## Funções já disponíveis

- Leitura automática das mídias internas do aparelho
- Exibição por álbuns e por conteúdo
- Suporte a fotos, vídeos, GIFs, SVGs e imagens RAW
- Reprodução de vídeos com Media3 / ExoPlayer
- Navegação fluida entre mídias por gesto
- Reprodução aleatória dentro do álbum
- Favoritar, compartilhar, ocultar, copiar, mover e excluir arquivos
- Criação de novas pastas
- Reordenação personalizada de mídias por arrastar e soltar
- Seleção múltipla de mídias e álbuns
- Filtro de mídia dentro dos álbuns
- Agrupamento por tipo, extensão e data
- Modo de visualização em grade e lista
- Ajuste global de espaçamento da grade
- Área de itens ocultos
- Editor básico de imagem com recorte e pincel
- Exportação de imagem para PDF
- Definir imagem como papel de parede
- Rotação de imagem
- Configurações de tema, comportamento e visualização

## Estrutura importante

- `app/src/main/kotlin/com/galeria/android/MainActivity.kt`
  Tela principal de álbuns
- `app/src/main/kotlin/com/galeria/android/AlbumMediaActivity.kt`
  Conteúdo interno de cada álbum
- `app/src/main/kotlin/com/galeria/android/DetailActivity.kt`
  Visualizador de foto e player de vídeo
- `app/src/main/kotlin/com/galeria/android/MediaStoreRepository.kt`
  Carregamento e indexação das mídias
- `app/src/main/kotlin/com/galeria/android/MediaActions.kt`
  Operações de mover, copiar, excluir, ocultar e criar pasta

## APK gerado

Build padrão do Gradle:

```text
app\build\outputs\apk\debug\app-debug.apk
```

APK versionado mantido na raiz do projeto:

```text
Galeria-Android-versao-0.7.apk
```

## Estado atual

- Código principal migrado para Kotlin
- Grade baseada em RecyclerView
- Melhorias contínuas de fluidez, cache local e carregamento progressivo
- App em evolução com foco em desempenho para bibliotecas grandes

## Próximos passos naturais

- Refinar cache de thumbnails e pré-carregamento
- Continuar a reduzir latência na primeira abertura
- Expandir o editor de imagem
- Evoluir organização personalizada de álbuns e mídias
