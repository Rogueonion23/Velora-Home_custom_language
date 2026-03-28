import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VeloraHome {

    //token types
    enum TokenType {
        ACTION,
        DEVICE,
        SPEED_MARK,
        SPEED_VALUE,
        SEPARATOR,
        WHITESPACE
    }

    //token class
    static class Token {
        TokenType type;
        String value;
        int position;

        Token(TokenType type, String value, int position) {
            this.type = type;
            this.value = value;
            this.position = position;
        }

        @Override
        public String toString() {
            return type + " -> " + value;
        }
    }

    //token definition
    static class TokenDef {
        TokenType type;
        Pattern pattern;

        TokenDef(TokenType type, String regex) {
            this.type = type;
            this.pattern = Pattern.compile(regex);
        }
    }

    //lexer
    static class Lexer {
        private final List<TokenDef> tokenDefs;

        Lexer() {
            tokenDefs = Arrays.asList(
                new TokenDef(TokenType.WHITESPACE, "^[ \\t\\r\\n]+"),
                new TokenDef(TokenType.ACTION, "^(IGNI|NEX|PULS|SURG|CALM|SYNC|SAFE)\\b"),
                new TokenDef(TokenType.DEVICE, "^(AERO|DRAPE|BOTIX|PUREX|GLIDE|FLOWR)\\b"),
                new TokenDef(TokenType.SPEED_MARK, "^V="),
                new TokenDef(TokenType.SPEED_VALUE, "^(([1-9][0-9]*m/s)|(x[1-9][0-9]*)|(slow|swift|rapid|storm))\\b"),
                new TokenDef(TokenType.SEPARATOR, "^;")
            );
        }

        public List<Token> tokenize(String input) {
            List<Token> tokens = new ArrayList<>();
            int globalPos = 0;

            while (!input.isEmpty()) {
                boolean matched = false;

                for (TokenDef def : tokenDefs) {
                    Matcher matcher = def.pattern.matcher(input);
                    if (matcher.find()) {
                        String value = matcher.group();

                        if (def.type != TokenType.WHITESPACE) {
                            tokens.add(new Token(def.type, value, globalPos));
                        }

                        input = input.substring(value.length());
                        globalPos += value.length();
                        matched = true;
                        break;
                    }
                }

                if (!matched) {
                    throw new RuntimeException(
                        "Erreur lexicale a la position " + globalPos +
                        " pres de: \"" + preview(input) + "\""
                    );
                }
            }

            return tokens;
        }

        private String preview(String s) {
            return s.length() <= 25 ? s : s.substring(0, 25) + "...";
        }
    }

    // =========================
    // INSTRUCTION MODEL
    // =========================
    static class Instruction {
        String action;
        List<String> devices = new ArrayList<>();
        String speedValue; // optional

        @Override
        public String toString() {
            return "Instruction{action=" + action +
                   ", devices=" + devices +
                   ", speedValue=" + speedValue + "}";
        }
    }

    // =========================
    // PARSER + VALIDATOR
    // =========================
    static class Parser {
        private final List<Token> tokens;
        private int index = 0;
        private final List<Instruction> instructions = new ArrayList<>();

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        public List<Instruction> parseProgram() {
            while (index < tokens.size()) {
                Instruction instr = parseInstruction();
                instructions.add(instr);
            }

            validateForbiddenSequences(instructions);
            return instructions;
        }

        private Instruction parseInstruction() {
            Token actionToken = expect(TokenType.ACTION);
            Instruction instr = new Instruction();
            instr.action = actionToken.value;

            switch (instr.action) {
                case "IGNI":
                case "NEX":
                case "SAFE":
                    instr.devices.add(expect(TokenType.DEVICE).value);
                    expect(TokenType.SEPARATOR);
                    break;

                case "PULS":
                case "SURG":
                case "CALM":
                    instr.devices.add(expect(TokenType.DEVICE).value);
                    expect(TokenType.SPEED_MARK);
                    instr.speedValue = expect(TokenType.SPEED_VALUE).value;
                    expect(TokenType.SEPARATOR);
                    break;

                case "SYNC":
                    instr.devices.add(expect(TokenType.DEVICE).value);
                    instr.devices.add(expect(TokenType.DEVICE).value);
                    expect(TokenType.SPEED_MARK);
                    instr.speedValue = expect(TokenType.SPEED_VALUE).value;
                    expect(TokenType.SEPARATOR);
                    break;

                default:
                    throw new RuntimeException("Action inconnue: " + instr.action);
            }

            return instr;
        }

        private Token expect(TokenType expected) {
            if (index >= tokens.size()) {
                throw new RuntimeException("Fin inattendue du programme, attendu: " + expected);
            }

            Token current = tokens.get(index);
            if (current.type != expected) {
                throw new RuntimeException(
                    "Erreur syntaxique a la position " + current.position +
                    ": attendu " + expected +
                    ", trouve " + current.type +
                    " (" + current.value + ")"
                );
            }

            index++;
            return current;
        }

        private void validateForbiddenSequences(List<Instruction> instructions) {
            // 1. SYNC X X este interzis
            for (Instruction instr : instructions) {
                if ("SYNC".equals(instr.action)) {
                    if (instr.devices.size() == 2 && instr.devices.get(0).equals(instr.devices.get(1))) {
                        throw new RuntimeException(
                            "Sequence interdite: SYNC ne peut pas synchroniser un dispositif avec lui-meme (" +
                            instr.devices.get(0) + ")."
                        );
                    }
                }
            }

            // 2. SAFE urmat imediat de SURG/PULS/CALM pe acelasi device este interzis
            for (int i = 0; i < instructions.size() - 1; i++) {
                Instruction a = instructions.get(i);
                Instruction b = instructions.get(i + 1);

                if ("SAFE".equals(a.action) && !a.devices.isEmpty() && !b.devices.isEmpty()) {
                    String devA = a.devices.get(0);

                    if (("SURG".equals(b.action) || "PULS".equals(b.action) || "CALM".equals(b.action))
                            && b.devices.get(0).equals(devA)) {
                        throw new RuntimeException(
                            "Sequence interdite: apres SAFE " + devA +
                            ", on ne peut pas appliquer immediatement " + b.action + "."
                        );
                    }
                }
            }

            // 3. IGNI urmat imediat de NEX pe acelasi device este interzis
            for (int i = 0; i < instructions.size() - 1; i++) {
                Instruction a = instructions.get(i);
                Instruction b = instructions.get(i + 1);

                if ("IGNI".equals(a.action) && "NEX".equals(b.action)
                        && !a.devices.isEmpty() && !b.devices.isEmpty()
                        && a.devices.get(0).equals(b.devices.get(0))) {
                    throw new RuntimeException(
                        "Sequence interdite: IGNI suivi immediatement de NEX pour le meme dispositif (" +
                        a.devices.get(0) + ")."
                    );
                }
            }
        }
    }

    // =========================
    // MAIN
    // =========================
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java VeloraHome <fichier.txt>");
            return;
        }

        try {
            String content = Files.readString(Path.of(args[0]));

            Lexer lexer = new Lexer();
            List<Token> tokens = lexer.tokenize(content);

            System.out.println("=== TOKENS RECONNUS ===");
            for (Token token : tokens) {
                System.out.println(token);
            }

            Parser parser = new Parser(tokens);
            List<Instruction> instructions = parser.parseProgram();

            System.out.println();
            System.out.println("=== INSTRUCTIONS VALIDES ===");
            for (Instruction instruction : instructions) {
                System.out.println(instruction);
            }

            System.out.println();
            System.out.println("Analyse lexicale et syntaxique reussie.");
        } catch (Exception e) {
            System.out.println("Erreur: " + e.getMessage());
        }
    }
}
