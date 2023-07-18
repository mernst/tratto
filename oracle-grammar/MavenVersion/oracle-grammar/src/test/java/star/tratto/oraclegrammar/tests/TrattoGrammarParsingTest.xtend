/*
 * generated by Xtext 2.29.0
 */
package star.tratto.oraclegrammar.tests

import com.google.inject.Inject
import org.eclipse.xtext.testing.InjectWith
import org.eclipse.xtext.testing.XtextRunner
import org.eclipse.xtext.testing.util.ParseHelper
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import star.tratto.oraclegrammar.trattoGrammar.Model

@RunWith(XtextRunner)
@InjectWith(TrattoGrammarInjectorProvider)
class TrattoGrammarParsingTest {
	@Inject
	ParseHelper<Model> parseHelper
	
	@Test
	def void loadModel() {
		val result = parseHelper.parse('''
			this.var.method() >= arg1+3 && Arrays.stream(arg2).anyMatch(jdVar -> jdVar < 5.1) || ((arg3.stream().allMatch(jdVar -> jdVar.equals("hello", 3)))) ? (methodResultID instanceof SomeClass)==false : someVar.someMethod(SomeClass.class, null);
		''')
		Assert.assertNotNull(result)
		val errors = result.eResource.errors
		Assert.assertTrue('''Unexpected errors: «errors.join(", ")»''', errors.isEmpty)
	}
}
