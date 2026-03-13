# Software Documentation (LaTeX)

Questa cartella contiene la documentazione tecnica completa del progetto in LaTeX.

## Struttura

- `main.tex`: file principale.
- `chapters/`: capitoli separati per area.
- `references.bib`: bibliografia.

## Compilazione

Dalla root del repository:

```bash
cd docs/software-doc-latex
pdflatex main.tex
pdflatex main.tex
```

Se usi citazioni BibTeX:

```bash
pdflatex main.tex
bibtex main
pdflatex main.tex
pdflatex main.tex
```

