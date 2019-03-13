package catdata.aql.exp;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.collections4.list.TreeList;

import catdata.Pair;
import catdata.Quad;
import catdata.Util;
import catdata.aql.AqlOptions;
import catdata.aql.AqlOptions.AqlOption;
import catdata.aql.Kind;
import catdata.aql.Mapping;
import catdata.aql.RawTerm;
import catdata.aql.Schema;
import catdata.aql.exp.SchExp.SchExpVar;
import catdata.aql.fdm.ColimitSchema;
import gnu.trove.map.hash.THashMap;
import gnu.trove.set.hash.THashSet;

public abstract class ColimSchExp<N> extends Exp<ColimitSchema<N>> {

	@Override
	public Kind kind() {
		return Kind.SCHEMA_COLIMIT;
	}

	public abstract SchExp getNode(N n, AqlTyping G);

	public abstract TyExp typeOf(AqlTyping G);

	public abstract Set<N> type(AqlTyping G);

	public abstract Set<Pair<SchExp, SchExp>> gotos(ColimSchExp<N> ths);

	@Override
	public Exp<ColimitSchema<N>> Var(String v) {
		Exp<ColimitSchema<N>> ret = new ColimSchExpVar<>(v);
		return ret;
	}

	public static interface ColimSchExpCoVisitor<R, P, E extends Exception> {
		public abstract <N> ColimSchExpQuotient<N> visitColimSchExpQuotient(P params, R exp) throws E;

		public abstract <N, Ex> ColimSchExpRaw<N, Ex> visitColimSchExpRaw(P params, R exp) throws E;

		public abstract <N> ColimSchExpVar<N> visitColimSchExpVar(P params, R exp) throws E;

		public abstract <N> ColimSchExpWrap<N> visitColimSchExpWrap(P params, R exp) throws E;

		public abstract <N> ColimSchExpModify<N> visitColimSchExpModify(P params, R exp) throws E;
	}

	public static interface ColimSchExpVisitor<R, P, E extends Exception> {
		public abstract <N> R visit(P params, ColimSchExpQuotient<N> exp) throws E;

		public abstract <N, Ex> R visit(P params, ColimSchExpRaw<N, Ex> exp) throws E;

		public abstract <N> R visit(P params, ColimSchExpVar<N> exp) throws E;

		public abstract <N> R visit(P params, ColimSchExpWrap<N> exp) throws E;

		public abstract <N> R visit(P params, ColimSchExpModify<N> exp) throws E;
	}

	public abstract <R, P, E extends Exception> R accept(P params, ColimSchExpVisitor<R, P, E> v) throws E;

	/////////////////////////////////////////////////////////////////

	public static class ColimSchExpQuotient<N> extends ColimSchExp<N> implements Raw {

		@Override
		public <R, P, E extends Exception> R accept(P param, ColimSchExpVisitor<R, P, E> v) throws E {
			return v.visit(param, this);
		}

		@Override
		protected void allowedOptions(Set<AqlOption> set) {
			set.add(AqlOption.allow_java_eqs_unsafe);
			set.add(AqlOption.simplify_names);
			set.add(AqlOption.left_bias);
		}

		private Map<String, List<InteriorLabel<Object>>> raw = new THashMap<>(Collections.emptyMap());

		@Override
		public Map<String, List<InteriorLabel<Object>>> raw() {
			return raw;
		}

		public final TyExp ty;

		public final Map<N, SchExp> nodes;

		public final Set<Quad<N, En, N, En>> eqEn;

		public final Set<Quad<String, String, RawTerm, RawTerm>> eqTerms;

		public final Set<Pair<List<String>, List<String>>> eqTerms2;

		@Override
		public Map<String, String> options() {
			return options;
		}

		public Map<String, String> options;

		@SuppressWarnings("unchecked")
		public ColimSchExpQuotient(TyExp ty, List<LocStr> nodes,
				List<Pair<Integer, Quad<String, String, String, String>>> eqEn,
				List<Pair<Integer, Quad<String, String, RawTerm, RawTerm>>> eqTerms,
				List<Pair<Integer, Pair<List<String>, List<String>>>> eqTerms2, List<Pair<String, String>> options) {
			this.ty = ty;
			this.nodes = new LinkedHashMap<>(nodes.size());
			this.eqEn = LocStr.proj2(eqEn).stream()
					.map(x -> new Quad<>((N) x.first, En.En(x.second), (N) x.third, En.En(x.fourth)))
					.collect(Collectors.toSet());
			this.eqTerms = LocStr.proj2(eqTerms);
			this.eqTerms2 = LocStr.proj2(eqTerms2);
			this.options = Util.toMapSafely(options);
			for (LocStr n : nodes) {
				if (this.nodes.containsKey(n.str)) {
					throw new RuntimeException("In schema colimit " + this + " duplicate schema " + n
							+ " - please create new schema variable if necessary.");
				}
				this.nodes.put((N) n.str, new SchExpVar(n.str));
			}

			List<InteriorLabel<Object>> f = new TreeList<>();
			for (Pair<Integer, Quad<String, String, String, String>> p : eqEn) {
				f.add(new InteriorLabel<>("entities", p.second, p.first,
						x -> x.first + "." + x.second + " = " + x.third + "." + x.fourth).conv());
			}
			raw.put("entities", f);

			f = new TreeList<>();
			for (Pair<Integer, Quad<String, String, RawTerm, RawTerm>> p : eqTerms) {
				f.add(new InteriorLabel<>("path eqs", p.second, p.first, x -> x.third + " = " + x.fourth).conv());
			}
			raw.put("path eqs", f);

			f = new TreeList<>();
			for (Pair<Integer, Pair<List<String>, List<String>>> p : eqTerms2) {
				f.add(new InteriorLabel<>("obs eqs", p.second, p.first,
						x -> Util.sep(x.first, ".") + " = " + Util.sep(x.second, ".")).conv());
			}
			raw.put("obs eqs", f);
		}

		@Override
		public synchronized ColimitSchema<N> eval0(AqlEnv env, boolean isC) {
			Map<N, Schema<Ty, En, Sym, Fk, Att>> nodes0 = new THashMap<>();
			Set<En> ens = new THashSet<>(nodes.size());
			for (N n : nodes.keySet()) {
				nodes0.put(n, nodes.get(n).eval(env, isC));
				ens.addAll(nodes0.get(n).ens.stream().map(x -> En.En(n + "_" + x)).collect(Collectors.toSet()));
			}

			return new ColimitSchema<>(nodes.keySet(), ty.eval(env, isC), nodes0, eqEn, eqTerms, eqTerms2,
					new AqlOptions(options, null, env.defaults));
		}

	
		@Override
		public String makeString() {
			final StringBuilder sb = new StringBuilder();

			if (nodes.keySet().isEmpty() & eqEn.isEmpty() && eqTerms.isEmpty() && eqTerms2.isEmpty()) {
				return sb.append("coproduct : ").append(this.ty).toString();
			}

			if (eqEn.isEmpty() && eqTerms.isEmpty() && eqTerms2.isEmpty()) {
				return sb.append("coproduct ").append(Util.sep(nodes.keySet(), " + ")).append(" : ").append(this.ty)
						.toString();
			}
			sb.append("quotient ").append(Util.sep(nodes.keySet(), " + ")).append(" : ").append(this.ty).append(" ")
					.append(" {\n");

			if (!eqEn.isEmpty()) {
				sb.append("\tentity_equations")
						.append(this.eqEn.stream().map(x -> x.first + "." + x.second + " = " + x.third + "." + x.fourth)
								.collect(Collectors.joining("\n\t\t", "\n\t\t", "\n")));
			}

			if (!eqTerms2.isEmpty()) {
				sb.append("\tpath_equations")
						.append(this.eqTerms2.stream()
								.map(x -> Util.sep(x.first, ".") + " = " + Util.sep(x.second, "."))
								.collect(Collectors.joining("\n\t\t", "\n\t\t", "\n")));
			}

			if (!eqTerms.isEmpty()) {
				sb.append("\tobservation_equations")
						.append(this.eqTerms.stream().map(x -> "forall " + x.first + ". " + x.third + " = " + x.fourth)
								.collect(Collectors.joining("\n\t\t", "\n\t\t", "\n")));
			}

			if (!options.isEmpty()) {
				sb.append("\toptions")
						.append(this.options.entrySet().stream().map(sym -> sym.getKey() + " = " + sym.getValue())
								.collect(Collectors.joining("\n\t\t", "\n\t\t", "\n")));
			}
			return sb.append("\n}").toString();
		}

		@Override
		public Collection<Pair<String, Kind>> deps() {
			Set<Pair<String, Kind>> ret = new THashSet<>();
			ret.addAll(ty.deps());
			for (SchExp v : nodes.values()) {
				ret.addAll(v.deps());
			}
			return ret;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((eqEn == null) ? 0 : eqEn.hashCode());
			result = prime * result + ((eqTerms == null) ? 0 : eqTerms.hashCode());
			result = prime * result + ((eqTerms2 == null) ? 0 : eqTerms2.hashCode());
			result = prime * result + ((nodes == null) ? 0 : nodes.hashCode());
			result = prime * result + ((options == null) ? 0 : options.hashCode());
			result = prime * result + ((ty == null) ? 0 : ty.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			ColimSchExpQuotient<?> other = (ColimSchExpQuotient<?>) obj;
			if (eqEn == null) {
				if (other.eqEn != null)
					return false;
			} else if (!eqEn.equals(other.eqEn))
				return false;
			if (eqTerms == null) {
				if (other.eqTerms != null)
					return false;
			} else if (!eqTerms.equals(other.eqTerms))
				return false;
			if (eqTerms2 == null) {
				if (other.eqTerms2 != null)
					return false;
			} else if (!eqTerms2.equals(other.eqTerms2))
				return false;
			if (nodes == null) {
				if (other.nodes != null)
					return false;
			} else if (!nodes.equals(other.nodes))
				return false;
			if (options == null) {
				if (other.options != null)
					return false;
			} else if (!options.equals(other.options))
				return false;
			if (ty == null) {
				if (other.ty != null)
					return false;
			} else if (!ty.equals(other.ty))
				return false;
			return true;
		}

		@Override
		public SchExp getNode(N n, AqlTyping G) {
			return nodes.get(n);
		}

		@Override
		public Set<N> type(AqlTyping G) {
			return nodes.keySet();
		}

		@Override
		public Set<Pair<SchExp, SchExp>> gotos(ColimSchExp<N> ths) {
			Set<Pair<SchExp, SchExp>> ret = new THashSet<>(nodes.size());
			SchExp t = new SchExpColim<>(ths);
			for (SchExp s : nodes.values()) {
				ret.add(new Pair<>(s, t));
			}
			return ret;
		}

		@Override
		public TyExp typeOf(AqlTyping G) {
			ty.type(G);
			for (N n : nodes.keySet()) {
				nodes.get(n).type(G);
			}
			return ty;
		}

		@Override
		public void mapSubExps(Consumer<Exp<?>> f) {
			ty.map(f);
			for (SchExp x : nodes.values()) {
				x.map(f);
			}
		}

	}

	/////////////////////////////////////////////////////////////////

	public static final class ColimSchExpVar<N> extends ColimSchExp<N> {
		public final String var;

		public <R, P, E extends Exception> R accept(P param, ColimSchExpVisitor<R, P, E> v) throws E {
			return v.visit(param, this);
		}

		@Override
		public Map<String, String> options() {
			return Collections.emptyMap();
		}

		@Override
		public boolean isVar() {
			return true;
		}

		@SuppressWarnings("unchecked")
		@Override
		public SchExp getNode(N n, AqlTyping G) {
			return ((ColimSchExp<N>) G.prog.exps.get(var)).getNode(n, G);
		}

		@Override
		public Collection<Pair<String, Kind>> deps() {
			return Collections.singleton(new Pair<>(var, Kind.SCHEMA_COLIMIT));
		}

		public ColimSchExpVar(String var) {
			this.var = var;
		}

		@Override
		public int hashCode() {
			return var.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			@SuppressWarnings("rawtypes")
			ColimSchExpVar other = (ColimSchExpVar) obj;
			return var.equals(other.var);
		}

		@Override
		public String toString() {
			return var;
		}

		@Override
		public synchronized ColimitSchema<N> eval0(AqlEnv env, boolean isC) {
			return env.defs.scs.get(var);
		}

		@SuppressWarnings("unchecked")
		@Override
		public Set<N> type(AqlTyping G) {
			Set<N> v = (Set<N>) G.defs.scs.get(var);
			Util.assertNotNull(v);
			return v;
		}

		@Override
		public TyExp typeOf(AqlTyping G) {
			return ((ColimSchExp<?>) G.prog.exps.get(var)).typeOf(G);
		}

		@Override
		public Set<Pair<SchExp, SchExp>> gotos(ColimSchExp<N> ths) {
			return Collections.emptySet();
		}

		@Override
		protected void allowedOptions(Set<AqlOption> set) {
		}

		@Override
		public void mapSubExps(Consumer<Exp<?>> f) {

		}

	}

	////////////////////////////////////////

	public static class ColimSchExpWrap<N> extends ColimSchExp<N> {

		public <R, P, E extends Exception> R accept(P param, ColimSchExpVisitor<R, P, E> v) throws E {
			return v.visit(param, this);
		}

		@Override
		public Set<Pair<SchExp, SchExp>> gotos(ColimSchExp<N> ths) {
			Set<Pair<SchExp, SchExp>> ret = new THashSet<>();
			SchExp t = new SchExpColim<>(ths);
			SchExp s = new SchExpColim<>(colim);
			ret.add(new Pair<>(s, t));
			return ret;
		}

		public final ColimSchExp<N> colim;

		public final MapExp toUser;

		public final MapExp fromUser;

		@Override
		public Map<String, String> options() {
			return Collections.emptyMap();
		}

		@Override
		public SchExp getNode(N n, AqlTyping G) {
			return colim.getNode(n, G);
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((colim == null) ? 0 : colim.hashCode());
			result = prime * result + ((fromUser == null) ? 0 : fromUser.hashCode());
			result = prime * result + ((toUser == null) ? 0 : toUser.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			ColimSchExpWrap<?> other = (ColimSchExpWrap<?>) obj;
			if (colim == null) {
				if (other.colim != null)
					return false;
			} else if (!colim.equals(other.colim))
				return false;
			if (fromUser == null) {
				if (other.fromUser != null)
					return false;
			} else if (!fromUser.equals(other.fromUser))
				return false;
			if (toUser == null) {
				if (other.toUser != null)
					return false;
			} else if (!toUser.equals(other.toUser))
				return false;
			return true;
		}

		public ColimSchExpWrap(ColimSchExp<N> colim, MapExp toUser,
				MapExp fromUser) {
			this.colim = colim;
			this.toUser = toUser;
			this.fromUser = fromUser;
		}

		@Override
		public Set<N> type(AqlTyping G) {
			return colim.type(G);
		}

		@Override
		public TyExp typeOf(AqlTyping G) {
			return colim.typeOf(G);
		}

		@Override
		public synchronized ColimitSchema<N> eval0(AqlEnv env, boolean isC) {
			return colim.eval(env, isC).wrap(toUser.eval(env, isC), fromUser.eval(env, isC));
		}

		@Override
		public String toString() {
			return "wrap " + colim + " " + toUser + " " + fromUser;
		}

		@Override
		public Collection<Pair<String, Kind>> deps() {
			return Util.union(colim.deps(), Util.union(toUser.deps(), fromUser.deps()));
		}

		@Override
		protected void allowedOptions(Set<AqlOption> set) {

		}

		@Override
		public void mapSubExps(Consumer<Exp<?>> f) {
			colim.map(f);
			toUser.map(f);
			fromUser.map(f);
		}

	}

	////////////////////////////////////////

	public static class ColimSchExpRaw<N, E> extends ColimSchExp<N> implements Raw {

		public <R, P, E extends Exception> R accept(P param, ColimSchExpVisitor<R, P, E> v) throws E {
			return v.visit(param, this);
		}

		@Override
		public Set<Pair<SchExp, SchExp>> gotos(ColimSchExp<N> ths) {
			Set<Pair<SchExp, SchExp>> ret = new THashSet<>();
			SchExp t = new SchExpColim<>(ths);
			for (SchExp s : nodes.values()) {
				ret.add(new Pair<>(s, t));
			}
			return ret;
		}

		private Map<String, List<InteriorLabel<Object>>> raw = new THashMap<>();

		@Override
		public Map<String, List<InteriorLabel<Object>>> raw() {
			return raw;
		}

		public final GraphExp<N, E> shape;

		public final TyExp ty;

		public final Map<N, SchExp> nodes;

		public final Map<E, MapExp> edges;

		public final Map<String, String> options;

		@Override
		public Map<String, String> options() {
			return options;
		}

		@Override
		public SchExp getNode(N n, AqlTyping G) {
			return nodes.get(n);
		}

		@SuppressWarnings("unchecked")
		public ColimSchExpRaw(GraphExp<N, E> shape, TyExp ty, List<Pair<LocStr, SchExp>> nodes,
				List<Pair<LocStr, MapExp>> edges,
				List<Pair<String, String>> options) {
			this.shape = shape;
			this.ty = ty;
			this.nodes = new LinkedHashMap<>();
			for (Pair<N, SchExp> xx : LocStr.list2(nodes, x -> (N) x)) {
				if (this.nodes.containsKey(xx.first)) {
					throw new RuntimeException("Duplicate node: " + xx.first);
				}
				this.nodes.put(xx.first, xx.second);
			}
			this.edges = Util.toMapSafely(LocStr.list2(edges, x -> (E) x));
			this.options = Util.toMapSafely(options);

			List<InteriorLabel<Object>> f = new TreeList<>();
			for (Pair<LocStr, SchExp> p : nodes) {
				f.add(new InteriorLabel<>("nodes", new Pair<>(p.first.str, p.second), p.first.loc,
						x -> x.first + " -> " + x.second).conv());
			}
			raw.put("nodes", f);

			f = new TreeList<>();
			for (Pair<LocStr, MapExp> p : edges) {
				f.add(new InteriorLabel<>("edges", new Pair<>(p.first.str, p.second), p.first.loc,
						x -> x.first + " -> " + x.second).conv());
			}
			raw.put("edges", f);

		}

		@Override
		public String makeString() {
			final StringBuilder sb = new StringBuilder();
			sb.append("literal " + shape + " : " + ty + " {");
			if (!nodes.isEmpty()) {
				sb.append("\n\tnodes\n\t\t");
				sb.append(Util.sep(nodes, " -> ", "\n\t\t"));
			}
			if (!edges.isEmpty()) {
				sb.append("\n\tedges\n\t\t");
				sb.append(Util.sep(edges, " -> ", "\n\t\t"));
			}
			sb.append("}");
			return sb.toString();
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((edges == null) ? 0 : edges.hashCode());
			result = prime * result + ((nodes == null) ? 0 : nodes.hashCode());
			result = prime * result + ((options == null) ? 0 : options.hashCode());
			result = prime * result + ((shape == null) ? 0 : shape.hashCode());
			result = prime * result + ((ty == null) ? 0 : ty.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			ColimSchExpRaw<?, ?> other = (ColimSchExpRaw<?, ?>) obj;
			if (edges == null) {
				if (other.edges != null)
					return false;
			} else if (!edges.equals(other.edges))
				return false;
			if (nodes == null) {
				if (other.nodes != null)
					return false;
			} else if (!nodes.equals(other.nodes))
				return false;
			if (options == null) {
				if (other.options != null)
					return false;
			} else if (!options.equals(other.options))
				return false;
			if (shape == null) {
				if (other.shape != null)
					return false;
			} else if (!shape.equals(other.shape))
				return false;
			if (ty == null) {
				if (other.ty != null)
					return false;
			} else if (!ty.equals(other.ty))
				return false;
			return true;
		}

		@Override
		public Collection<Pair<String, Kind>> deps() {
			Collection<Pair<String, Kind>> ret = new THashSet<>();
			for (SchExp k : nodes.values()) {
				ret.addAll(k.deps());
			}
			for (MapExp k : edges.values()) {
				ret.addAll(k.deps());
			}
			ret.addAll(shape.deps());
			ret.addAll(ty.deps());
			return ret;
		}

		@Override
		public synchronized ColimitSchema<N> eval0(AqlEnv env, boolean isC) {
			Map<N, Schema<Ty, En, Sym, Fk, Att>> nodes0 = new THashMap<>(nodes.size());
			for (N n : nodes.keySet()) {
				nodes0.put(n, nodes.get(n).eval(env, isC));
			}
			Map<E, Mapping> edges0 = new THashMap<>(edges.size());
			for (E e : edges.keySet()) {
				edges0.put(e, edges.get(e).eval(env, isC));
			}
			return new ColimitSchema(nodes.keySet(), shape.eval(env, isC).dmg, ty.eval(env, isC), nodes0, edges0,
					new AqlOptions(options, null, env.defaults));
		}

		@Override
		public Set<N> type(AqlTyping G) {
			return nodes.keySet();
		}

		@Override
		protected void allowedOptions(Set<AqlOption> set) {
			set.add(AqlOption.allow_java_eqs_unsafe);
			set.add(AqlOption.simplify_names);
			set.add(AqlOption.left_bias);
		}

		@Override
		public TyExp typeOf(AqlTyping G) {
			return ty;
		}

		@Override
		public void mapSubExps(Consumer<Exp<?>> f) {
			ty.map(f);
			shape.map(f);
			for (SchExp k : nodes.values()) {
				k.map(f);
			}
			for (MapExp k : edges.values()) {
				k.map(f);
			}
		}
	}

	///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

}