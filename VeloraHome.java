import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VeloraHome {

    // tipuri de tokenuri
    enum TokenType {
        ACTION,
        DEVICE,
        SPEED_MARK,
        SPEED_VALUE,
        SEPARATOR,
        WHITESPACE
    }

    // clasa token
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

    // definirea unui token
    static class TokenDef {
        TokenType type;
        Pattern pattern;

        TokenDef(TokenType type, String regex) {
            this.type = type;
            this.pattern = Pattern.compile(regex);
        }
    }

    // analizor lexical
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
                        "Eroare lexicala la pozitia " + globalPos +
                        " langa: \"" + preview(input) + "\""
                    );
                }
            }

            return tokens;
        }

        private String preview(String s) {
            return s.length() <= 25 ? s : s.substring(0, 25) + "...";
        }
    }

    // model pentru instructiuni
    static class Instruction {
        String action;
        List<String> devices = new ArrayList<>();
        String speedValue; // optional

        @Override
        public String toString() {
            return "Instruction{actiune=" + action +
                   ", dispozitive=" + devices +
                   ", valoareViteza=" + speedValue + "}";
        }
    }

    // parser + validare

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
                    throw new RuntimeException("Actiune necunoscuta: " + instr.action);
            }

            return instr;
        }

        private Token expect(TokenType expected) {
            if (index >= tokens.size()) {
                throw new RuntimeException("Sfarsit neasteptat al programului, se astepta: " + expected);
            }

            Token current = tokens.get(index);
            if (current.type != expected) {
                throw new RuntimeException(
                    "Eroare sintactica la pozitia " + current.position +
                    ": se astepta " + expected +
                    ", dar s-a gasit " + current.type +
                    " (" + current.value + ")"
                );
            }

            index++;
            return current;
        }

        private void validateForbiddenSequences(List<Instruction> instructions) {
            // 1. SYNC x x este interzis
            for (Instruction instr : instructions) {
                if ("SYNC".equals(instr.action)) {
                    if (instr.devices.size() == 2 && instr.devices.get(0).equals(instr.devices.get(1))) {
                        throw new RuntimeException(
                            "Secventa interzisa: SYNC nu poate sincroniza un dispozitiv cu el insusi (" +
                            instr.devices.get(0) + ")."
                        );
                    }
                }
            }

            // 2. SAFE urmat imediat de SURG/PULS/CALM pe acelasi dispozitiv este interzis
            for (int i = 0; i < instructions.size() - 1; i++) {
                Instruction a = instructions.get(i);
                Instruction b = instructions.get(i + 1);

                if ("SAFE".equals(a.action) && !a.devices.isEmpty() && !b.devices.isEmpty()) {
                    String devA = a.devices.get(0);

                    if (("SURG".equals(b.action) || "PULS".equals(b.action) || "CALM".equals(b.action))
                            && b.devices.get(0).equals(devA)) {
                        throw new RuntimeException(
                            "Secventa interzisa: dupa SAFE " + devA +
                            ", nu se poate aplica direct " + b.action + "."
                        );
                    }
                }
            }

            // 3. IGNI urmat imediat de NEX pe acelasi dispozitiv este interzis
            for (int i = 0; i < instructions.size() - 1; i++) {
                Instruction a = instructions.get(i);
                Instruction b = instructions.get(i + 1);

                if ("IGNI".equals(a.action) && "NEX".equals(b.action)
                        && !a.devices.isEmpty() && !b.devices.isEmpty()
                        && a.devices.get(0).equals(b.devices.get(0))) {
                    throw new RuntimeException(
                        "Secventa interzisa: IGNI urmat imediat de NEX pentru acelasi dispozitiv (" +
                        a.devices.get(0) + ")."
                    );
                }
            }
        }
    }
public static void main(String[] args) {

    // lista fisierelor de test
    String[] testFiles = {
        "test1.txt",
        "test2.txt",
        "test3.txt",
        "test4.txt",
        "test5.txt"
    };

    for (String fileName : testFiles) {
        System.out.println("\n------------------------------");
        System.out.println("RULARE FISIER: " + fileName);
        System.out.println("-------------------------------");

        try {
            // citire fisier
            String content = Files.readString(Path.of(fileName));

            // analizor lexical
            Lexer lexer = new Lexer();
            List<Token> tokens = lexer.tokenize(content);

            System.out.println("\n--- TOKENURI ---");
            for (Token token : tokens) {
                System.out.println(token);
            }

            // parser + validare
            Parser parser = new Parser(tokens);
            List<Instruction> instructions = parser.parseProgram();

            System.out.println("\n--- INSTRUCTIUNI VALIDATE ---");
            for (Instruction instr : instructions) {
                System.out.println(instr);
            }

            System.out.println("\n SUCCES: fisier valid");

        } catch (Exception e) {
            System.out.println("\n EROARE in fisier: " + fileName);
            System.out.println("Motiv: " + e.getMessage());
        }
    }
}
}
