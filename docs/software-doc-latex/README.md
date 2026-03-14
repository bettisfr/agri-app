# Software Documentation (LaTeX)

Questa cartella contiene la documentazione tecnica completa del progetto in LaTeX.

## Struttura

- `main.tex`: file principale.
- `sections/`: sezioni separate per area (incluse da `main.tex`).
- `references.bib`: bibliografia.

## Compilazione

Dalla root del repository:

```bash
cd docs/software-doc-latex
pdflatex -interaction=nonstopmode -halt-on-error main.tex
pdflatex -interaction=nonstopmode -halt-on-error main.tex
```

Se usi citazioni BibTeX:

```bash
pdflatex -interaction=nonstopmode -halt-on-error main.tex
bibtex main
pdflatex -interaction=nonstopmode -halt-on-error main.tex
pdflatex -interaction=nonstopmode -halt-on-error main.tex
```
