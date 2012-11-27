/**
 * 
 */
package de.jutzig.jabylon.rest.ui.wicket.panels;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.wicket.AttributeModifier;
import org.apache.wicket.behavior.AttributeAppender;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.TextArea;
import org.apache.wicket.markup.html.panel.GenericPanel;
import org.apache.wicket.markup.repeater.RepeatingView;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.model.PropertyModel;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import de.jutzig.jabylon.properties.PropertiesFactory;
import de.jutzig.jabylon.properties.Property;
import de.jutzig.jabylon.properties.Review;
import de.jutzig.jabylon.properties.Severity;
import de.jutzig.jabylon.rest.ui.model.EObjectModel;
import de.jutzig.jabylon.rest.ui.model.PropertyPair;

/**
 * @author Johannes Utzig (jutzig.dev@googlemail.com)
 * 
 */
public class SinglePropertyEditor extends GenericPanel<PropertyPair> {

	static final String OK_LABEL = "OK";
	private static final long serialVersionUID = 1L;
	private boolean expanded;
	private static List<IModel<Review>> OK_REVIEW;
	
	
	static{
		IModel<Review> noReviewModel = new NoReviewModel();
		OK_REVIEW = Collections.singletonList(noReviewModel);
	}

	public SinglePropertyEditor(String id, IModel<PropertyPair> model, boolean expanded, Collection<Review> reviews) {
		super(id, model);
		this.expanded = expanded;
		setOutputMarkupId(true);
		PropertyPair propertyPair = model.getObject();

		Property template = propertyPair.getTemplate();
		String key = null;
		if (template != null)
			key = propertyPair.getTemplate().getKey();
		else
			key = propertyPair.getTranslation().getKey();

		final Label icon = new Label("icon");
		// String iconName = isExpanded() ? "icon-chevron-down" :
		// "icon-chevron-right";
		// icon.add(new AttributeModifier("class", iconName));
		icon.setOutputMarkupId(true);
		add(icon);

		fillStatusColumn(propertyPair, reviews);

		final WebMarkupContainer templatePanel = new WebMarkupContainer("template-area");
		templatePanel.add(new AttributeModifier("class", isExpanded() ? "collapse in" : "collapse"));
		templatePanel.setOutputMarkupId(true);
		add(templatePanel);
		final WebMarkupContainer translationPanel = new WebMarkupContainer("translation-area");
		translationPanel.add(new AttributeModifier("class", isExpanded() ? "collapse in" : "collapse"));
		translationPanel.setOutputMarkupId(true);
		add(translationPanel);

		final Label translationLabel = new Label("translation-label", new PropertyModel<PropertyPair>(propertyPair, "translated"));
		WebMarkupContainer toggletButton = new WebMarkupContainer("toggle");
		add(toggletButton);

		add(new Label("key-label", key));
		add(translationLabel);

		TextArea<PropertyPair> textArea = new TextArea<PropertyPair>("template", new PropertyModel<PropertyPair>(propertyPair, "original"));
		templatePanel.add(textArea);

		textArea = new TextArea<PropertyPair>("template-comment", new PropertyModel<PropertyPair>(propertyPair, "originalComment"));
		templatePanel.add(textArea);

		textArea = new TextArea<PropertyPair>("translation-comment", new PropertyModel<PropertyPair>(propertyPair, "translatedComment"));
		translationPanel.add(textArea);

		textArea = new TextArea<PropertyPair>("translation", new PropertyModel<PropertyPair>(propertyPair, "translated"));
		translationPanel.add(textArea);

	}

	protected void fillStatusColumn(PropertyPair propertyPair, Collection<Review> reviewCollection) {
		IStatus status = calculateRowStatus(propertyPair);
//		Label statusIcon = new Label("status-icon");
//		add(statusIcon);
//		statusIcon.add(new AttributeModifier("class", status.isOK() ? "icon-ok-circle" : "icon-warning-sign"));
		if (status.getSeverity() == IStatus.WARNING)
			add(new AttributeModifier("class", "warning"));
		else if (status.getSeverity() == IStatus.ERROR)
			add(new AttributeModifier("class", "error"));
		
		ArrayList<IModel<Review>> reviewModel = createReviewListModel(reviewCollection);
		
		RepeatingView view = new RepeatingView("reviews");
		for (IModel<Review> model : reviewModel) {
			Review review = model.getObject();
			Label label = new Label(view.newChildId(),review.getReviewType());
			label.add(new AttributeAppender("class", getLabelClass(review)));
			if(review.getMessage()!=null)
				label.add(new AttributeModifier("title", review.getMessage()));
			view.add(label);
		}

		add(view);

	}

	private ArrayList<IModel<Review>> createReviewListModel(Collection<Review> reviewCollection) {
		if (reviewCollection == null || reviewCollection.isEmpty()) 
			return new ArrayList<IModel<Review>>(OK_REVIEW);
			
		ArrayList<IModel<Review>> list = new ArrayList<IModel<Review>>(reviewCollection.size());
		for (Review review : reviewCollection) {
			list.add(new EObjectModel<Review>(review));
		}
		return list;
	}

	protected String getLabelClass(Review review) {
		if (review.getReviewType().equals(OK_LABEL))
			return " label-success";

		Severity severity = review.getSeverity();
		switch (severity) {
		case ERROR:
			return " label-important";
		case INFO:
			return " label-info";
		case WARNING:
			return " label-warning";
		default:
			return "";
		}
	}

	private IStatus calculateRowStatus(PropertyPair propertyPair) {

		if (propertyPair.getOriginal() == null || propertyPair.getOriginal().isEmpty())
			return new Status(IStatus.ERROR, "de.jutzig.jabylon.rest.ui", "");
		else if (propertyPair.getTranslated() == null || propertyPair.getTranslated().isEmpty())
			return new Status(IStatus.ERROR, "de.jutzig.jabylon.rest.ui", "");
		return Status.OK_STATUS;
	}

	// public void setExpanded(boolean expanded) {
	// this.expanded = expanded;
	// }
	//
	public boolean isExpanded() {
		return expanded;
	}

}

class NoReviewModel extends LoadableDetachableModel<Review>
{
	private static final long serialVersionUID = 1L;

	@Override
	protected Review load() {
		Review review = PropertiesFactory.eINSTANCE.createReview();
		review.setReviewType(SinglePropertyEditor.OK_LABEL);
		return review;
	}
}
