package net.vvakame.util.jsonpullparser.factory;

import static javax.lang.model.util.ElementFilter.typesIn;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;

import net.vvakame.util.jsonpullparser.JsonFormatException;
import net.vvakame.util.jsonpullparser.JsonPullParser;
import net.vvakame.util.jsonpullparser.JsonPullParser.State;
import net.vvakame.util.jsonpullparser.annotation.JsonHash;
import net.vvakame.util.jsonpullparser.annotation.JsonKey;
import net.vvakame.util.jsonpullparser.factory.ClassWriterHelper.Mode;

@SupportedSourceVersion(SourceVersion.RELEASE_6)
@SupportedAnnotationTypes("net.vvakame.util.jsonpullparser.annotation.*")
public class JsonAnnotationProcessor extends AbstractProcessor {

	private static final String CLASS_POSTFIX_OPTION = "JsonPullParserClassPostfix";
	private static String classPostfix;

	Set<? extends TypeElement> annotations;
	RoundEnvironment roundEnv;

	@Override
	public synchronized void init(ProcessingEnvironment processingEnv) {
		super.init(processingEnv);
		Log.init(processingEnv.getMessager());
	}

	@Override
	public boolean process(Set<? extends TypeElement> annotations,
			RoundEnvironment roundEnv) {

		this.annotations = annotations;
		this.roundEnv = roundEnv;

		// 生成するクラスのpostfixが指定されてたらそっちにする
		Map<String, String> options = processingEnv.getOptions();
		if (options.containsKey(CLASS_POSTFIX_OPTION)
				&& !"".equals(options.get(CLASS_POSTFIX_OPTION))) {
			classPostfix = options.get(CLASS_POSTFIX_OPTION);
		} else {
			classPostfix = "Gen";
		}

		for (Element element : typesIn(roundEnv
				.getElementsAnnotatedWith(JsonHash.class))) {

			try {
				ClassWriterHelper w;
				w = new ClassWriterHelper(processingEnv, element, classPostfix,
						Mode.Mock);
				genSupportClass(w, element);
				w.close();
				// 構文上のエラーに遭遇していたら処理を中断する
				if (w.isEncountError()) {
					continue;
				}

				w = new ClassWriterHelper(processingEnv, element, classPostfix,
						Mode.Real);
				genSupportClass(w, element);
				w.close();
			} catch (IOException e) {
				Log.e(e.getMessage());
			}
		}

		return true;
	}

	void genSupportClass(ClassWriterHelper w, Element classElement) {
		// package名出力
		w.writePackage();

		// コメント出力
		w.wr("// Do you know Ctrl(Command)+Shift+M?\n");

		// class宣言出力
		w.writeClassSignature();
		w.incrementIndent();

		genMethodGetList(w, classElement);

		genMethodGet(w, classElement);

		w.decrementIndent();
		w.wr("}");

		w.flush();
	}

	private void genMethodGetList(ClassWriterHelper w, Element classElement) {
		w.wr("public static ").writeListClassName();
		w.wr(" getList(").wr(JsonPullParser.class).wr(" parser) throws ");
		w.wr(IOException.class).wr(", ");
		w.wr(JsonFormatException.class).wr("{").lni();

		// 結果用変数生成
		w.wr().writeListClassName().wr(" list = new ").writeListInstance();
		w.wr("();").ln();
		// 最初のbraceを食べる
		w.wr(State.class).wr(" eventType = parser.getEventType();").ln();
		w.wr("if (eventType != ").wr(State.class).wr(".").wr(State.START_ARRAY);
		w.wr(") {").lni();
		w.wr("throw new IllegalStateException(\"not started brace!\");").lnd();
		w.wr("}").ln();
		// ループ処理共通部分生成
		w.wr("while (parser.lookAhead() != ");
		w.wr(State.class).wr(".").wr(State.END_ARRAY);
		w.wr("){").lni();
		w.wr("list.add(get(parser));").lnd();
		w.wr("}").ln();
		w.wr("parser.getEventType();").ln();
		// 返り値の処理
		w.wr("return list;").lnd();
		w.wr("}").ln();
	}

	private void genMethodGet(ClassWriterHelper w, Element classElement) {
		w.wr("public static ").writeClassName();
		w.wr(" get(").wr(JsonPullParser.class).wr(" parser) throws ");
		w.wr(IOException.class).wr(", ");
		w.wr(JsonFormatException.class).wr("{").lni();

		// 結果用変数生成
		w.wr().writeClassName().wr(" obj = new ").writeClassName().wr("();");
		w.ln();
		// 最初のbraceを食べる
		w.wr(State.class).wr(" eventType = parser.getEventType();").ln();
		w.wr("if (eventType != ").wr(State.class).wr(".").wr("START_HASH");
		w.wr(") {").lni();
		w.wr("throw new IllegalStateException(\"not started hash brace!\");");
		w.lnd();
		w.wr("}").ln();
		// ループ処理共通部分生成
		w.wr("while ((eventType = parser.getEventType()) != ");
		w.wr(State.class).wr(".").wr(State.END_HASH.toString());
		w.wr("){").lni();
		w.wr("if (eventType != ").wr(State.class).wr(".KEY) {").lni();
		w.wr("throw new IllegalStateException(\"expect KEY. we got unexpected value. \" + eventType);");
		w.lnd();
		w.wr("}").ln();
		w.wr("String key = parser.getValueString();").ln();

		// 値の独自処理
		// JsonKeyの収集
		List<Element> elements = filterJsonKeyElement(classElement);
		// JsonKeyに対応する値取得コードを生成する
		boolean first = true;
		for (Element element : elements) {
			w.setHolder(element);
			if (first) {
				first = false;
			} else {
				w.wr("else ");
			}
			genExtractValues(w, element);
		}
		w.lnd();
		w.wr("}").ln();
		// 返り値の処理
		w.wr("return obj;").lnd();
		w.wr("}").ln();
	}

	private void genExtractValues(ClassWriterHelper w, Element element) {
		element.asType().accept(new ValueExtractVisitor(), w);
	}

	private List<Element> filterJsonKeyElement(Element parent) {
		List<? extends Element> elements = parent.getEnclosedElements();
		List<Element> results = new ArrayList<Element>();

		for (Element element : elements) {
			if (element.getKind() != ElementKind.FIELD) {
				continue;
			}
			JsonKey key = element.getAnnotation(JsonKey.class);
			if (key == null) {
				continue;
			}
			results.add(element);
		}

		return results;
	}

	String getElementKeyString(Element element) {
		JsonKey key = element.getAnnotation(JsonKey.class);
		return "".equals(key.value()) ? element.toString() : key.value();
	}

	Element getElementAccessor(Element element) {
		Element setter = null;
		for (Element m : ElementFilter.methodsIn(element.getEnclosingElement()
				.getEnclosedElements())) {
			if (("set" + element.getSimpleName().toString()).equalsIgnoreCase(m
					.getSimpleName().toString())) {
				// TODO publicかどうかの判定をいれていない
				setter = m;
				break;
			} else if (element.getSimpleName().toString().startsWith("is")
					&& ("set" + element.getSimpleName().toString().substring(2))
							.equalsIgnoreCase(m.getSimpleName().toString())) {
				// boolean isHoge のsetterは setHoge になる
				// TODO publicかどうかの判定をいれていない
				setter = m;
				break;
			}
		}
		if (setter != null) {
			return setter;
		} else {
			return null;
		}
	}

	class ValueExtractVisitor extends
			StandardTypeKindVisitor<Void, ClassWriterHelper> {

		@Override
		public Void visitPrimitiveAsBoolean(PrimitiveType t, ClassWriterHelper p) {

			Element element = p.getHolder();
			Element accessor = getElementAccessor(element);
			if (accessor == null) {
				Log.e("can't find accessor method", p.getHolder());
				p.setEncountError(true);
				return defaultAction(t, p);
			}
			writeIfHeader(p);
			p.wr("eventType = parser.getEventType();");
			p.wr("obj.").wr(accessor.getSimpleName().toString()).wr("(");
			p.wr("parser.getValueBoolean()");
			writeIfFooter(p);
			return super.visitPrimitiveAsBoolean(t, p);
		}

		@Override
		public Void visitPrimitiveAsByte(PrimitiveType t, ClassWriterHelper p) {

			Element element = p.getHolder();
			Element accessor = getElementAccessor(element);
			if (accessor == null) {
				Log.e("can't find accessor method", p.getHolder());
				p.setEncountError(true);
				return defaultAction(t, p);
			}
			writeIfHeader(p);
			p.wr("eventType = parser.getEventType();");
			p.wr("obj.").wr(accessor.getSimpleName().toString()).wr("(");
			p.wr("(byte)parser.getValueLong()");
			writeIfFooter(p);
			return super.visitPrimitiveAsByte(t, p);
		}

		@Override
		public Void visitPrimitiveAsChar(PrimitiveType t, ClassWriterHelper p) {

			Element element = p.getHolder();
			Element accessor = getElementAccessor(element);
			if (accessor == null) {
				Log.e("can't find accessor method", p.getHolder());
				p.setEncountError(true);
				return defaultAction(t, p);
			}
			writeIfHeader(p);
			p.wr("eventType = parser.getEventType();");
			p.wr("obj.").wr(accessor.getSimpleName().toString()).wr("(");
			p.wr("parser.getValueString().charAt(0)");
			writeIfFooter(p);
			return super.visitPrimitiveAsChar(t, p);
		}

		@Override
		public Void visitPrimitiveAsDouble(PrimitiveType t, ClassWriterHelper p) {

			Element element = p.getHolder();
			Element accessor = getElementAccessor(element);
			if (accessor == null) {
				Log.e("can't find accessor method", p.getHolder());
				p.setEncountError(true);
				return defaultAction(t, p);
			}
			writeIfHeader(p);
			p.wr("eventType = parser.getEventType();");
			p.wr("obj.").wr(accessor.getSimpleName().toString()).wr("(");
			p.wr("parser.getValueDouble()");
			writeIfFooter(p);
			return super.visitPrimitiveAsDouble(t, p);
		}

		@Override
		public Void visitPrimitiveAsFloat(PrimitiveType t, ClassWriterHelper p) {

			Element element = p.getHolder();
			Element accessor = getElementAccessor(element);
			if (accessor == null) {
				Log.e("can't find accessor method", p.getHolder());
				p.setEncountError(true);
				return defaultAction(t, p);
			}
			writeIfHeader(p);
			p.wr("eventType = parser.getEventType();");
			p.wr("obj.").wr(accessor.getSimpleName().toString()).wr("(");
			p.wr("(float)parser.getValueDouble()");
			writeIfFooter(p);
			return super.visitPrimitiveAsFloat(t, p);
		}

		@Override
		public Void visitPrimitiveAsInt(PrimitiveType t, ClassWriterHelper p) {

			Element element = p.getHolder();
			Element accessor = getElementAccessor(element);
			if (accessor == null) {
				Log.e("can't find accessor method", p.getHolder());
				p.setEncountError(true);
				return defaultAction(t, p);
			}
			writeIfHeader(p);
			p.wr("eventType = parser.getEventType();");
			p.wr("obj.").wr(accessor.getSimpleName().toString()).wr("(");
			p.wr("(int)parser.getValueLong()");
			writeIfFooter(p);
			return super.visitPrimitiveAsInt(t, p);
		}

		@Override
		public Void visitPrimitiveAsLong(PrimitiveType t, ClassWriterHelper p) {

			Element element = p.getHolder();
			Element accessor = getElementAccessor(element);
			if (accessor == null) {
				Log.e("can't find accessor method", p.getHolder());
				p.setEncountError(true);
				return defaultAction(t, p);
			}
			writeIfHeader(p);
			p.wr("eventType = parser.getEventType();");
			p.wr("obj.").wr(accessor.getSimpleName().toString()).wr("(");
			p.wr("parser.getValueLong()");
			writeIfFooter(p);
			return super.visitPrimitiveAsLong(t, p);
		}

		@Override
		public Void visitPrimitiveAsShort(PrimitiveType t, ClassWriterHelper p) {

			Element element = p.getHolder();
			Element accessor = getElementAccessor(element);
			if (accessor == null) {
				Log.e("can't find accessor method", p.getHolder());
				p.setEncountError(true);
				return defaultAction(t, p);
			}
			writeIfHeader(p);
			p.wr("eventType = parser.getEventType();");
			p.wr("obj.").wr(accessor.getSimpleName().toString()).wr("(");
			p.wr("(short)parser.getValueLong()");
			writeIfFooter(p);
			return super.visitPrimitiveAsShort(t, p);
		}

		@Override
		public Void visitString(DeclaredType t, ClassWriterHelper p) {

			Element element = p.getHolder();
			Element accessor = getElementAccessor(element);
			if (accessor == null) {
				Log.e("can't find accessor method", p.getHolder());
				p.setEncountError(true);
				return defaultAction(t, p);
			}
			writeIfHeader(p);
			p.wr("eventType = parser.getEventType();");
			p.wr("obj.").wr(accessor.getSimpleName().toString()).wr("(");
			p.wr("parser.getValueString()");
			writeIfFooter(p);
			return super.visitString(t, p);
		}

		@Override
		public Void visitList(DeclaredType t, ClassWriterHelper p) {

			List<? extends TypeMirror> generics = t.getTypeArguments();
			if (generics.size() != 1) {
				Log.e("expected single type generics.", p.getHolder());
				p.setEncountError(true);
				return defaultAction(t, p);
			}
			TypeMirror tm = generics.get(0);
			if (tm instanceof WildcardType) {
				WildcardType wt = (WildcardType) tm;
				TypeMirror extendsBound = wt.getExtendsBound();
				if (extendsBound != null) {
					tm = extendsBound;
				}
				TypeMirror superBound = wt.getSuperBound();
				if (superBound != null) {
					tm = superBound;
				}
			}

			Element type = processingEnv.getTypeUtils().asElement(tm);
			JsonHash hash = type.getAnnotation(JsonHash.class);
			if (hash == null) {
				Log.e("expect for use decorated class by JsonHash annotation.",
						p.getHolder());
				p.setEncountError(true);
				return defaultAction(t, p);
			}

			writeIfHeader(p);
			Element element = p.getHolder();
			Element accessor = getElementAccessor(element);
			if (accessor == null) {
				Log.e("can't find accessor method", p.getHolder());
				p.setEncountError(true);
				return defaultAction(t, p);
			}
			p.wr("obj.").wr(accessor.getSimpleName().toString()).wr("(");
			String generatedClassName = p.getGenerateCanonicalClassName(tm);
			p.wr(generatedClassName).wr(".getList(parser)");
			writeIfFooter(p);

			return super.visitList(t, p);
		}

		@Override
		public Void visitUndefinedClass(DeclaredType t, ClassWriterHelper p) {

			writeIfHeader(p);
			Element element = p.getHolder();
			Element accessor = getElementAccessor(element);
			if (accessor == null) {
				Log.e("can't find accessor method", p.getHolder());
				p.setEncountError(true);
				return defaultAction(t, p);
			}
			p.wr("obj.").wr(accessor.getSimpleName().toString()).wr("(");
			String generatedClassName = p.getGenerateCanonicalClassName(t);
			p.wr(generatedClassName).wr(".get(parser)");
			writeIfFooter(p);
			return super.visitUndefinedClass(t, p);
		}

		void writeIfHeader(ClassWriterHelper w) {
			Element element = w.getHolder();
			w.wr("if(\"").wr(getElementKeyString(element))
					.wr("\".equals(key)){").lni();
		}

		void writeIfFooter(ClassWriterHelper w) {
			w.wr(");").lnd();
			w.wr("}");
		}
	}
}
