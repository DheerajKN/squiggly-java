package com.github.bohnman.squiggly.parser;

import com.github.bohnman.squiggly.config.SquigglyConfig;
import com.github.bohnman.squiggly.metric.SquigglyMetrics;
import com.github.bohnman.squiggly.metric.source.GuavaCacheSquigglyMetricsSource;
import com.github.bohnman.squiggly.name.AnyDeepName;
import com.github.bohnman.squiggly.name.AnyShallowName;
import com.github.bohnman.squiggly.name.ExactName;
import com.github.bohnman.squiggly.name.RegexName;
import com.github.bohnman.squiggly.name.SquigglyName;
import com.github.bohnman.squiggly.name.VariableName;
import com.github.bohnman.squiggly.name.WildcardName;
import com.github.bohnman.squiggly.parser.antlr4.SquigglyExpressionBaseVisitor;
import com.github.bohnman.squiggly.parser.antlr4.SquigglyExpressionLexer;
import com.github.bohnman.squiggly.parser.antlr4.SquigglyExpressionParser;
import com.github.bohnman.squiggly.util.antlr4.ThrowingErrorListener;
import com.github.bohnman.squiggly.view.PropertyView;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.jcip.annotations.ThreadSafe;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;

/**
 * The parser takes a filter expression and compiles it to an Abstract Syntax Tree (AST).  In this parser's case, the
 * tree doesn't have a root node but rather just returns top level nodes.
 */
@ThreadSafe
public class SquigglyParser {

    public static final String IDENTITY_FUNCTION = "identity";
    // Caches parsed filter expressions
    private final Cache<String, List<SquigglyNode>> cache;

    public SquigglyParser(SquigglyConfig config, SquigglyMetrics metrics) {
        cache = CacheBuilder.from(config.getParserNodeCacheSpec()).build();
        metrics.add(new GuavaCacheSquigglyMetricsSource("squiggly.parser.nodeCache.", cache));
    }

    /**
     * Parse a filter expression.
     *
     * @param filter the filter expression
     * @return compiled nodes
     */
    public List<SquigglyNode> parse(String filter) {
        filter = StringUtils.trim(filter);

        if (StringUtils.isEmpty(filter)) {
            return Collections.emptyList();
        }

        // get it from the cache if we can
        List<SquigglyNode> cachedNodes = cache.getIfPresent(filter);

        if (cachedNodes != null) {
            return cachedNodes;
        }


        SquigglyExpressionLexer lexer = ThrowingErrorListener.overwrite(new SquigglyExpressionLexer(CharStreams.fromString(filter)));
        SquigglyExpressionParser parser = ThrowingErrorListener.overwrite(new SquigglyExpressionParser(new CommonTokenStream(lexer)));

        Visitor visitor = new Visitor();
        List<SquigglyNode> nodes = Collections.unmodifiableList(visitor.visit(parser.parse()));

        cache.put(filter, nodes);
        return nodes;
    }

    private class Visitor extends SquigglyExpressionBaseVisitor<List<SquigglyNode>> {
        @Override
        public List<SquigglyNode> visitParse(SquigglyExpressionParser.ParseContext ctx) {
            MutableNode root = new MutableNode(parseContext(ctx), new ExactName("root")).dotPathed(true);
            handleExpressionList(ctx.expressionList(), root);
            MutableNode analyzedRoot = analyze(root);
            return analyzedRoot.toSquigglyNode().getChildren();
        }

        private ParseContext parseContext(ParserRuleContext ctx) {
            Token start = ctx.getStart();
            return new ParseContext(start.getLine(), start.getCharPositionInLine());
        }

        private void handleExpressionList(SquigglyExpressionParser.ExpressionListContext ctx, MutableNode parent) {
            List<SquigglyExpressionParser.ExpressionContext> expressions = ctx.expression();

            for (SquigglyExpressionParser.ExpressionContext expressionContext : expressions) {
                handleExpression(expressionContext, parent);
            }
        }

        private void handleExpression(SquigglyExpressionParser.ExpressionContext ctx, MutableNode parent) {

            if (ctx.negatedExpression() != null) {
                handleNegatedExpression(ctx.negatedExpression(), parent);
            }

            List<SquigglyName> names;
            List<ParserRuleContext> ruleContexts;

            if (ctx.field() != null) {
                names = Collections.singletonList(createName(ctx.field()));
                ruleContexts = Collections.singletonList(ctx.field());
            } else if (ctx.dottedField() != null) {
                parent.squiggly = true;
                for (int i = 0; i < ctx.dottedField().field().size() - 1; i++) {
                    SquigglyExpressionParser.FieldContext field = ctx.dottedField().field(i);
                    parent = parent.addChild(new MutableNode(parseContext(field), createName(field)).dotPathed(true));
                    parent.squiggly = true;
                }
                SquigglyExpressionParser.FieldContext lastField = ctx.dottedField().field().get(ctx.dottedField().field().size() - 1);
                names = Collections.singletonList(createName(lastField));
                ruleContexts = Collections.singletonList(lastField);
            } else if (ctx.fieldList() != null) {
                names = new ArrayList<>(ctx.fieldList().field().size());
                ruleContexts = new ArrayList<>(ctx.fieldList().field().size());
                for (SquigglyExpressionParser.FieldContext fieldContext : ctx.fieldList().field()) {
                    names.add(createName(fieldContext));
                    ruleContexts.add(fieldContext);
                }
            } else if (ctx.wildcardDeepField() != null) {
                names = Collections.singletonList(AnyDeepName.get());
                ruleContexts = Collections.singletonList(ctx.wildcardDeepField());
            } else {
                ruleContexts = Collections.singletonList(ctx);
                names = Collections.emptyList();
            }

            List<FunctionNode> keyFunctions = Collections.emptyList();
            List<FunctionNode> valueFunctions = Collections.emptyList();

            if (ctx.fieldFunctionChain() != null) {
                if (ctx.fieldFunctionChain().keyFunctionChain() != null) {
                    keyFunctions = parseKeyFunctionChain(ctx.fieldFunctionChain().keyFunctionChain());
                }

                if (ctx.fieldFunctionChain().valueFunctionChain() != null) {
                    valueFunctions = parseValueFunctionChain(ctx.fieldFunctionChain().valueFunctionChain(), ctx.fieldFunctionChain().functionSeparator());
                }
            }

            for (int i = 0; i < names.size(); i++) {
                SquigglyName name = names.get(i);
                ParserRuleContext ruleContext = ruleContexts.get(i);
                MutableNode node = parent.addChild(new MutableNode(parseContext(ruleContext), name));
                node.keyFunctions(keyFunctions);
                node.valueFunctions(valueFunctions);

                if (ctx.emptyNestedExpression() != null) {
                    node.emptyNested = true;
                } else if (ctx.nestedExpression() != null) {
                    node.squiggly = true;
                    handleExpressionList(ctx.nestedExpression().expressionList(), node);
                }
            }
        }

        private List<FunctionNode> parseKeyFunctionChain(SquigglyExpressionParser.KeyFunctionChainContext functionChainContext) {
            return parseFunctionChain(functionChainContext.functionChain(), null, true);
        }

        private List<FunctionNode> parseValueFunctionChain(SquigglyExpressionParser.ValueFunctionChainContext functionChainContext, SquigglyExpressionParser.FunctionSeparatorContext separatorContext) {
            return parseFunctionChain(functionChainContext.functionChain(), separatorContext, true);
        }

        private List<FunctionNode> parseFunctionChain(SquigglyExpressionParser.FunctionChainContext chainContext) {
            return parseFunctionChain(chainContext, null, false);
        }

        @SuppressWarnings("CodeBlock2Expr")
        private List<FunctionNode> parseFunctionChain(SquigglyExpressionParser.FunctionChainContext chainContext, SquigglyExpressionParser.FunctionSeparatorContext separatorContext, boolean input) {
            List<FunctionNode> functions = new ArrayList<>(chainContext.functionWithSeparator().size() + 1);
            functions.add(parseFunction(chainContext.function(), separatorContext, input));

            chainContext.functionWithSeparator().forEach(ctx -> {
                functions.add(parseFunction(ctx.function(), ctx.functionSeparator(), true));
            });

            return functions;
        }

        private FunctionNode parseFunction(SquigglyExpressionParser.FunctionContext functionContext, SquigglyExpressionParser.FunctionSeparatorContext separatorContext, boolean input) {
            ParseContext context = parseContext(functionContext);
            FunctionNode.Builder builder = buildBaseFunction(functionContext, context);

            if (input) {
                builder.parameter(baseArg(functionContext, ArgumentNodeType.INPUT).value(ArgumentNodeType.INPUT));
            }

            applyParameters(builder, functionContext);

            if (separatorContext != null && "?.".equals(separatorContext.getText())) {
                builder.ignoreNulls(true);
            }

            return builder.build();
        }


        private FunctionNode.Builder buildBaseFunction(SquigglyExpressionParser.FunctionContext functionContext, ParseContext context) {
            return FunctionNode.builder()
                    .context(context)
                    .name(functionContext.functionName().getText());
        }

        private void applyParameters(FunctionNode.Builder builder, SquigglyExpressionParser.FunctionContext functionContext) {
            SquigglyExpressionParser.FunctionParametersContext functionParametersContext = functionContext.functionParameters();

            if (functionParametersContext != null) {
                functionParametersContext.functionParameter().forEach(parameter -> applyParameter(builder, parameter));
            }
        }

        private void applyParameter(FunctionNode.Builder builder, SquigglyExpressionParser.FunctionParameterContext parameter) {
            ArgumentNode.Builder arg = buildArg(parameter.arg());
            builder.parameter(arg);
        }

        private ArgumentNode.Builder buildArg(SquigglyExpressionParser.ArgContext arg) {
            Object value;
            ArgumentNodeType type;

            if (arg.argChain() != null) {
                return buildArgChain(arg.argChain());
            }

            if (arg.intRange() != null) {
                return buildIntRange(arg.intRange());
            }

            if (arg.literal() != null) {
                return buildLiteral(arg.literal());
            }

            if (arg.variable() != null) {
                return buildVariable(arg.variable());
            }

            if (arg.lambda() != null) {
                return buildLambda(arg.lambda());
            }

            if (arg.arg() != null && !arg.arg().isEmpty()) {
                return buildSubArg(arg);
            }


            throw new IllegalStateException(format("%s: Unknown arg type [%s]", parseContext(arg), arg.getText()));
        }

        private ArgumentNode.Builder buildLambda(SquigglyExpressionParser.LambdaContext lambda) {
            List<String> arguments = lambda.lambdaArg()
                    .stream()
                    .map(arg -> arg.variable() == null ? "_" : buildVariableValue(arg.variable()))
                    .collect(toList());

            ParseContext parseContext = parseContext(lambda);
            FunctionNode body = FunctionNode.builder()
                    .name(IDENTITY_FUNCTION)
                    .context(parseContext)
                    .parameter(buildArg(lambda.lambdaBody().arg()))
                    .build();


            LambdaNode lambdaNode = new LambdaNode(parseContext, arguments, body);

            return baseArg(lambda, ArgumentNodeType.LAMBDA)
                    .value(lambdaNode);
        }

        private ArgumentNode.Builder buildSubArg(SquigglyExpressionParser.ArgContext arg) {
            if (arg.binaryOperator() != null) {
                return buildBinaryExpression(arg);
            }

            if (arg.prefixOperator() != null) {
                return buildPrefixExpression(arg);
            }

            if (arg.argGroupStart() != null) {
                return buildArg(arg.arg(0));
            }

            throw new IllegalStateException(format("%s: Unknown sub-arg type [%s]", parseContext(arg), arg.getText()));
        }

        private ArgumentNode.Builder buildPrefixExpression(SquigglyExpressionParser.ArgContext arg) {
            String op = getOp(arg.prefixOperator(), arg.prefixOperator().getText());

            ParseContext parseContext = parseContext(arg);

            FunctionNode functionNode = FunctionNode.builder()
                    .context(parseContext)
                    .name(op)
                    .parameter(buildArg(arg.arg(0)))
                    .build();

            return baseArg(arg, ArgumentNodeType.FUNCTION_CHAIN)
                    .value(Collections.singletonList(functionNode));
        }

        private ArgumentNode.Builder buildBinaryExpression(SquigglyExpressionParser.ArgContext arg) {
            String op = getOp(arg.binaryOperator(), arg.binaryOperator().getText());

            ParseContext parseContext = parseContext(arg);

            FunctionNode functionNode = FunctionNode.builder()
                    .context(parseContext)
                    .name(op)
                    .parameter(buildArg(arg.arg(0)))
                    .parameter(buildArg(arg.arg(1)))
                    .build();

            return baseArg(arg, ArgumentNodeType.FUNCTION_CHAIN)
                    .value(Collections.singletonList(functionNode));
        }

        private String getOp(ParserRuleContext context, String text) {
            switch (text) {
                case "add":
                case "+":
                    return "add";
                case "sub":
                case "-":
                    return "sub";
                case "mul":
                case "*":
                    return "mul";
                case "div":
                case "/":
                    return "div";
                case "mod":
                case "%":
                    return "mod";
                case "eq":
                case "==":
                    return "equals";
                case "ne":
                case "!=":
                    return "nequals";
                case "lt":
                case "<":
                    return "lt";
                case "lte":
                case "<=":
                    return "lte";
                case "gt":
                case ">":
                    return "gt";
                case "gte":
                case ">=":
                    return "gte";
                case "match":
                case "=~":
                    return "match";
                case "nmatch":
                case "!~":
                    return "nmatch";
                case "or":
                case "||":
                    return "or";
                case "and":
                case "&&":
                    return "and";
                case "not":
                case "!":
                    return "not";
                default:
                    throw new IllegalStateException(format("%s: unknown op [%s]", parseContext(context), context.getText()));
            }
        }

        private ArgumentNode.Builder buildLiteral(SquigglyExpressionParser.LiteralContext context) {
            if (context.BooleanLiteral() != null) {
                return buildBoolean(context);
            }


            if (context.FloatLiteral() != null) {
                return buildFloat(context);
            }

            if (context.IntegerLiteral() != null) {
                return buildInteger(context);
            }

            if (context.RegexLiteral() != null) {
                return buildRegex(context);
            }

            if (context.StringLiteral() != null) {
                return buildString(context);
            }

            throw new IllegalStateException(format("%s: Unknown literal type [%s]", parseContext(context), context.getText()));
        }

        private ArgumentNode.Builder buildIntRange(SquigglyExpressionParser.IntRangeContext context) {
            List<SquigglyExpressionParser.IntRangeArgContext> intRangeArgs = context.intRangeArg();
            ArgumentNode.Builder start = null;
            ArgumentNode.Builder end = null;

            if (intRangeArgs.size() > 0) {
                start = buildIntRangeArg(intRangeArgs.get(0));
            }

            if (intRangeArgs.size() > 1) {
                end = buildIntRangeArg(intRangeArgs.get(1));
            }

            return baseArg(context, ArgumentNodeType.INT_RANGE)
                    .value(new IntRangeNode(start, end));
        }

        private ArgumentNode.Builder buildIntRangeArg(SquigglyExpressionParser.IntRangeArgContext context) {
            if (context.variable() != null) {
                return buildVariable(context.variable());
            }

            if (context.IntegerLiteral() != null) {
                return buildInteger(context);
            }


            throw new IllegalStateException(format("%s: Unknown int range arg type [%s]", parseContext(context), context.getText()));
        }

        private ArgumentNode.Builder baseArg(ParserRuleContext context, ArgumentNodeType type) {
            ParseContext parseContext = parseContext(context);
            return ArgumentNode.builder().context(parseContext).type(type);
        }

        private ArgumentNode.Builder buildBoolean(ParserRuleContext context) {
            return baseArg(context, ArgumentNodeType.BOOLEAN)
                    .value("true".equalsIgnoreCase(context.getText()));
        }

        private ArgumentNode.Builder buildFloat(ParserRuleContext context) {
            return baseArg(context, ArgumentNodeType.FLOAT).value(Float.parseFloat(context.getText()));
        }

        private ArgumentNode.Builder buildArgChain(SquigglyExpressionParser.ArgChainContext context) {
            int functionLength = (context.functionChain() == null) ? 0 : context.functionChain().functionWithSeparator().size();
            functionLength += 2;
            List<FunctionNode> functionNodes = new ArrayList<>(functionLength);

            if (context.literal() != null) {
                functionNodes.add(FunctionNode.builder()
                        .context(parseContext(context.literal()))
                        .name(IDENTITY_FUNCTION)
                        .parameter(buildLiteral(context.literal()))
                        .build()
                );
            }

            if (context.intRange() != null) {
                functionNodes.add(FunctionNode.builder()
                        .context(parseContext(context.intRange()))
                        .name(IDENTITY_FUNCTION)
                        .parameter(buildIntRange(context.intRange()))
                        .build()
                );
            }

            if (context.variable() != null) {
                functionNodes.add(FunctionNode.builder()
                        .context(parseContext(context.variable()))
                        .name(IDENTITY_FUNCTION)
                        .parameter(buildVariable(context.variable()))
                        .build()
                );
            }

            if (context.propertyChain() != null) {
                // TODO: finish
            }

            if (context.directionalPropertyChain() != null) {
                // TODO: finish
            }

            if (context.functionChain() != null) {
                functionNodes.addAll(parseFunctionChain(context.functionChain(), context.functionSeparator(), !functionNodes.isEmpty()));
            }

            return baseArg(context, ArgumentNodeType.FUNCTION_CHAIN)
                    .value(functionNodes);
        }

        private ArgumentNode.Builder buildInteger(ParserRuleContext context) {
            return baseArg(context, ArgumentNodeType.INTEGER).value(Integer.parseInt(context.getText()));
        }


        private ArgumentNode.Builder buildRegex(ParserRuleContext context) {
            return baseArg(context, ArgumentNodeType.REGEX).value(buildPattern(context.getText()));
        }

        private ArgumentNode.Builder buildString(ParserRuleContext context) {
            return baseArg(context, ArgumentNodeType.STRING).value(unescapeString(context.getText()));
        }

        private String unescapeString(String text) {
            if (text == null) {
                return null;
            }

            if (text.length() < 2) {
                return text;
            }

            if (text.startsWith("'") && text.endsWith("'")) {
                text = StringEscapeUtils.unescapeEcmaScript(text.substring(1, text.length() - 1));
            }

            if (text.startsWith("\"") && text.endsWith("\"")) {
                text = StringEscapeUtils.unescapeEcmaScript(text.substring(1, text.length() - 1));
            }

            return text;
        }

        private ArgumentNode.Builder buildVariable(SquigglyExpressionParser.VariableContext context) {
            return baseArg(context, ArgumentNodeType.VARIABLE).value(buildVariableValue(context));
        }

        private String buildVariableValue(SquigglyExpressionParser.VariableContext context) {
            return unescapeString(context.getText().substring(1));
        }

        private SquigglyName createName(SquigglyExpressionParser.FieldContext ctx) {
            SquigglyName name;

            if (ctx.StringLiteral() != null) {
                name = new ExactName(unescapeString(ctx.StringLiteral().getText()));
            } else if (ctx.IntegerLiteral() != null) {
                name = new ExactName(ctx.IntegerLiteral().getText());
            } else if (ctx.binaryNamedOperator() != null) {
                name = new ExactName(ctx.binaryNamedOperator().getText());
            } else if (ctx.prefixNamedOperator() != null) {
                name = new ExactName(ctx.prefixNamedOperator().getText());
            } else if (ctx.Identifier() != null) {
                name = new ExactName(ctx.Identifier().getText());
            } else if (ctx.wildcardField() != null) {
                name = new WildcardName(ctx.wildcardField().getText());
            } else if (ctx.RegexLiteral() != null) {


                Pattern pattern = buildPattern(ctx.RegexLiteral().getText());

                name = new RegexName(pattern.pattern(), pattern);
            } else if (ctx.WildcardLiteral() != null) {
                if ("*".equals(ctx.WildcardLiteral().getText())) {
                    name = AnyShallowName.get();
                } else {
                    name = new WildcardName(ctx.WildcardLiteral().getText());
                }
            } else if (ctx.variable() != null) {
                name = new VariableName(buildVariableValue(ctx.variable()));
            } else {
                throw new IllegalArgumentException("Unhandled field: " + ctx.getText());
            }

            return name;
        }

        private Pattern buildPattern(String fullPattern) {
            String pattern = fullPattern.substring(1);
            int slashIdx = pattern.indexOf('/');

            if (slashIdx < 0) {
                slashIdx = pattern.indexOf('~');
            }

            Set<String> flags = new HashSet<>();

            if (slashIdx >= 0) {
                String flagPart = StringUtils.trim(StringUtils.substring(pattern, slashIdx + 1));
                pattern = StringUtils.substring(pattern, 0, slashIdx);

                if (StringUtils.isNotEmpty(flagPart)) {
                    for (char flag : flagPart.toCharArray()) {
                        flags.add(Character.toString(flag));
                    }
                }
            }

            int flagMask = 0;

            if (flags != null && !flags.isEmpty()) {
                for (String flag : flags) {
                    switch (flag) {
                        case "i":
                            flagMask |= Pattern.CASE_INSENSITIVE;
                            break;
                        default:
                            throw new IllegalArgumentException("Unrecognized flag " + flag + " for pattern " + pattern);
                    }
                }
            }

            return Pattern.compile(pattern, flagMask);
        }


        private void handleNegatedExpression(SquigglyExpressionParser.NegatedExpressionContext ctx, MutableNode parent) {
            if (ctx.field() != null) {
                parent.addChild(new MutableNode(parseContext(ctx.field()), createName(ctx.field())).negated(true));
            } else if (ctx.dottedField() != null) {
                for (SquigglyExpressionParser.FieldContext fieldContext : ctx.dottedField().field()) {
                    parent.squiggly = true;
                    parent = parent.addChild(new MutableNode(parseContext(ctx.dottedField()), createName(fieldContext)).dotPathed(true));
                }

                parent.negated(true);
            }
        }

    }

    private MutableNode analyze(MutableNode node) {
        if (node.children != null && !node.children.isEmpty()) {
            boolean allNegated = true;

            for (MutableNode child : node.children.values()) {
                if (!child.negated) {
                    allNegated = false;
                    break;
                }
            }

            if (allNegated) {
                node.addChild(new MutableNode(node.getContext(), newBaseViewName()).dotPathed(node.dotPathed));
                MutableNode parent = node.parent;

                while (parent != null) {
                    parent.addChild(new MutableNode(parent.getContext(), newBaseViewName()).dotPathed(parent.dotPathed));

                    if (!parent.dotPathed) {
                        break;
                    }

                    parent = parent.parent;
                }
            } else {
                for (MutableNode child : node.children.values()) {
                    analyze(child);
                }
            }

        }

        return node;
    }

    private class MutableNode {
        private final ParseContext context;
        private SquigglyName name;
        private boolean negated;
        private boolean squiggly;
        private boolean emptyNested;
        @Nullable
        private Map<String, MutableNode> children;
        private boolean dotPathed;
        @Nullable
        private MutableNode parent;
        private List<FunctionNode> keyFunctions = new ArrayList<>();
        private List<FunctionNode> valueFunctions = new ArrayList<>();

        MutableNode(ParseContext context, SquigglyName name) {
            this.context = context;
            this.name = name;
        }

        SquigglyNode toSquigglyNode() {
            if (name == null) {
                throw new IllegalArgumentException("No Names specified");
            }

            List<SquigglyNode> childNodes;

            if (children == null || children.isEmpty()) {
                childNodes = Collections.emptyList();
            } else {
                childNodes = new ArrayList<>(children.size());

                for (MutableNode child : children.values()) {
                    childNodes.add(child.toSquigglyNode());
                }
            }

            return new SquigglyNode(context, name, childNodes, keyFunctions, valueFunctions, negated, squiggly, emptyNested);
        }

        public ParseContext getContext() {
            return context;
        }

        public MutableNode dotPathed(boolean dotPathed) {
            this.dotPathed = dotPathed;
            return this;
        }

        @SuppressWarnings("UnusedReturnValue")
        public MutableNode keyFunctions(List<FunctionNode> functions) {
            functions.forEach(this::keyFunction);
            return this;
        }

        public MutableNode keyFunction(FunctionNode function) {
            keyFunctions.add(function);
            return this;
        }

        @SuppressWarnings("UnusedReturnValue")
        public MutableNode valueFunctions(List<FunctionNode> functions) {
            functions.forEach(this::valueFunction);
            return this;
        }

        public MutableNode valueFunction(FunctionNode function) {
            valueFunctions.add(function);
            return this;
        }

        public MutableNode negated(boolean negated) {
            this.negated = negated;
            return this;
        }

        public MutableNode addChild(MutableNode childToAdd) {
            if (children == null) {
                children = new LinkedHashMap<>();
            }

            String name = childToAdd.name.getName();
            MutableNode existingChild = children.get(name);

            if (existingChild == null) {
                childToAdd.parent = this;
                children.put(name, childToAdd);
            } else {
                if (childToAdd.children != null) {

                    if (existingChild.children == null) {
                        existingChild.children = childToAdd.children;
                    } else {
                        existingChild.children.putAll(childToAdd.children);
                    }
                }


                existingChild.squiggly = existingChild.squiggly || childToAdd.squiggly;
                existingChild.emptyNested = existingChild.emptyNested && childToAdd.emptyNested;
                existingChild.dotPathed = existingChild.dotPathed && childToAdd.dotPathed;
                childToAdd = existingChild;
            }

            if (!childToAdd.dotPathed && dotPathed) {
                dotPathed = false;
            }

            return childToAdd;
        }

    }

    private ExactName newBaseViewName() {
        return new ExactName(PropertyView.BASE_VIEW);
    }


}
