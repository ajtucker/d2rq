package de.fuberlin.wiwiss.d2rq.find;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.util.iterator.ExtendedIterator;
import com.hp.hpl.jena.util.iterator.NullIterator;

import de.fuberlin.wiwiss.d2rq.algebra.JoinOptimizer;
import de.fuberlin.wiwiss.d2rq.algebra.Relation;
import de.fuberlin.wiwiss.d2rq.algebra.TripleRelation;
import de.fuberlin.wiwiss.d2rq.find.URIMakerRule.URIMakerRuleChecker;
import de.fuberlin.wiwiss.d2rq.plan.ExecuteSequence;
import de.fuberlin.wiwiss.d2rq.plan.ExecuteTripleRelation;
import de.fuberlin.wiwiss.d2rq.plan.ExecutionPlanElement;
import de.fuberlin.wiwiss.d2rq.plan.ExecutionPlanVisitor;
import de.fuberlin.wiwiss.d2rq.plan.ExecuteCompatibleTripleRelations;
import de.fuberlin.wiwiss.d2rq.sql.ApplyTripleMakerIterator;
import de.fuberlin.wiwiss.d2rq.sql.SelectStatementBuilder;
import de.fuberlin.wiwiss.d2rq.sql.TripleMaker;


/**
 * A find query on a list of property bridges. Results are delivered
 * as an iterator. Will combine queries on multiple bridges into one
 * SQL statement where possible.
 *
 * @author Richard Cyganiak (richard@cyganiak.de)
 * @version $Id: FindQuery.java,v 1.12 2008/04/25 11:25:06 cyganiak Exp $
 */
public class FindQuery {
	private List compatibleRelations = new ArrayList();
	
	public FindQuery(Triple triplePattern, Collection propertyBridges) {
		URIMakerRule rule = new URIMakerRule();
		propertyBridges = rule.sortRDFRelations(propertyBridges);
		URIMakerRuleChecker subjectChecker = rule.createRuleChecker(triplePattern.getSubject());
		URIMakerRuleChecker objectChecker = rule.createRuleChecker(triplePattern.getObject());
		Iterator it = propertyBridges.iterator();
		while (it.hasNext()) {
			TripleRelation bridge = (TripleRelation) it.next();
			TripleRelation selectionBridge = bridge.selectTriple(triplePattern);
			if (selectionBridge != null
					&& subjectChecker.canMatch(bridge.nodeMaker(TripleRelation.SUBJECT_NODE_MAKER))
					&& objectChecker.canMatch(bridge.nodeMaker(TripleRelation.OBJECT_NODE_MAKER))) {
				subjectChecker.addPotentialMatch(bridge.nodeMaker(TripleRelation.SUBJECT_NODE_MAKER));
				objectChecker.addPotentialMatch(bridge.nodeMaker(TripleRelation.OBJECT_NODE_MAKER));
				addRelation(new JoinOptimizer(selectionBridge).optimize());
			}
		}
	}
	
	private void addRelation(TripleRelation relation) {
		Iterator it = this.compatibleRelations.iterator();
		while (it.hasNext()) {
			List queries = (List) it.next();
			if (ExecuteCompatibleTripleRelations.areCompatible((TripleRelation) queries.get(0), relation)) {
				queries.add(relation);
				return;
			}
		}
		List newList = new ArrayList();
		newList.add(relation);
		this.compatibleRelations.add(newList);
	}

	private ExecutionPlanElement createPlan() {
		ExecuteSequence sequence = new ExecuteSequence();
		Iterator it = compatibleRelations.iterator();
		while (it.hasNext()) {
			List tripleRelations = (List) it.next();
			sequence.add(createPlanForTripleRelationList(tripleRelations));
		}
		return sequence;
	}
	
	private ExecutionPlanElement createPlanForTripleRelationList(List tripleRelations) {
		if (tripleRelations.size() == 1) {
			return new ExecuteTripleRelation((TripleRelation) tripleRelations.get(0));
		}
		return new ExecuteCompatibleTripleRelations(tripleRelations);
	}
	
	public ExtendedIterator iterator() {
		TripleIteratorVisitor visitor = new TripleIteratorVisitor();
		createPlan().visit(visitor);
		return visitor.iterator();
	}
	
	private class TripleIteratorVisitor implements ExecutionPlanVisitor {
		private ExtendedIterator iterator = new NullIterator();
		public ExtendedIterator iterator() {
			return iterator;
		}
		public void visit(ExecuteTripleRelation planElement) {
			TripleRelation relation = planElement.getTripleRelation();
			chain(relation.baseRelation(), relation.projectionSpecs(),
					relation.isUnique(), relation);
		}
		public void visit(ExecuteCompatibleTripleRelations union) {
			chain(union.baseRelation(), union.projectionSpecs(), true, union);
		}
		public void visit(ExecuteSequence sequence) {
			Iterator it = sequence.elements().iterator();
			while (it.hasNext()) {
				ExecutionPlanElement element = (ExecutionPlanElement) it.next();
				element.visit(this);
			}
		}
		public void visitEmptyPlanElement() {
			// Do nothing
		}
		private void chain(Relation relation, Set projections, boolean isUnique, 
				TripleMaker tripleMaker) {
			SelectStatementBuilder sql = new SelectStatementBuilder(relation.database());
			sql.setEliminateDuplicates(!isUnique);
			sql.addSelectSpecs(projections);
			sql.addRelation(relation);
			iterator = iterator.andThen(
					new ApplyTripleMakerIterator(sql.execute(), tripleMaker));
		}
	}
}