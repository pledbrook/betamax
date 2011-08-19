package betamax

import betamax.storage.json.JsonTapeLoader
import java.text.ParseException
import org.apache.http.HttpResponse
import org.apache.http.entity.ByteArrayEntity
import org.apache.http.message.BasicHttpResponse
import betamax.storage.*
import groovy.json.*
import static java.net.HttpURLConnection.*
import static org.apache.http.HttpHeaders.*
import static org.apache.http.HttpVersion.HTTP_1_1
import org.apache.http.client.methods.*
import spock.lang.*

class TapeLoadingSpec extends Specification {

	TapeLoader loader = new JsonTapeLoader()

	@Shared HttpGet getRequest
	@Shared HttpPost postRequest
	@Shared HttpResponse successResponse
	@Shared HttpResponse failureResponse

	def setupSpec() {
		getRequest = new HttpGet("http://icanhascheezburger.com/")

		postRequest = new HttpPost("http://github.com/")
		postRequest.entity = new ByteArrayEntity("q=1".bytes)

		successResponse = new BasicHttpResponse(HTTP_1_1, HTTP_OK, "OK")
		successResponse.addHeader(CONTENT_TYPE, "text/plain")
		successResponse.addHeader(CONTENT_LANGUAGE, "en-GB")
		successResponse.addHeader(CONTENT_ENCODING, "gzip")
		successResponse.entity = new ByteArrayEntity("O HAI!".bytes)

		failureResponse = new BasicHttpResponse(HTTP_1_1, HTTP_BAD_REQUEST, "BAD REQUEST")
		failureResponse.addHeader(CONTENT_TYPE, "text/plain")
		failureResponse.addHeader(CONTENT_LANGUAGE, "en-GB")
		failureResponse.addHeader(CONTENT_ENCODING, "gzip")
		failureResponse.entity = new ByteArrayEntity("KTHXBYE!".bytes)
	}

	def "can write a tape to storage"() {
		given:
		def tape = new Tape(name: "tape_loading_spec")
		def writer = new StringWriter()

		when:
		tape.record(getRequest, successResponse)
		loader.writeTape(tape, writer)

		then:
		def json = new JsonSlurper().parseText(writer.toString())
		json.tape.name == tape.name

		json.tape.interactions.size() == 1
		json.tape.interactions[0].recorded ==~ /\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} [\+-]\d{4}/

		json.tape.interactions[0].request.protocol == "HTTP/1.1"
		json.tape.interactions[0].request.method == "GET"
		json.tape.interactions[0].request.uri == "http://icanhascheezburger.com/"
		json.tape.interactions[0].response.protocol == "HTTP/1.1"
		json.tape.interactions[0].response.status == HTTP_OK
		json.tape.interactions[0].response.body == "O HAI!"
	}

	def "can write requests with a body"() {
		given:
		def tape = new Tape(name: "tape_loading_spec")
		def writer = new StringWriter()

		when:
		tape.record(postRequest, successResponse)
		loader.writeTape(tape, writer)

		then:
		def json = new JsonSlurper().parseText(writer.toString())
		json.tape.interactions[0].request.method == "POST"
		json.tape.interactions[0].request.body == "q=1"
	}

	def "can write multiple interactions"() {
		given:
		def tape = new Tape(name: "tape_loading_spec")
		def writer = new StringWriter()

		when:
		tape.record(getRequest, successResponse)
		tape.record(postRequest, failureResponse)
		loader.writeTape(tape, writer)

		then:
		def json = new JsonSlurper().parseText(writer.toString())
		json.tape.interactions.size() == 2
		json.tape.interactions[0].request.method == "GET"
		json.tape.interactions[1].request.method == "POST"
		json.tape.interactions[0].response.status == HTTP_OK
		json.tape.interactions[1].response.status == HTTP_BAD_REQUEST
	}

	def "can load a valid tape with a single interaction"() {
		given:
		def json = """\
{
	"tape": {
		"name": "single_interaction_tape",
		"interactions": [
			{
				"recorded": "2011-08-19 12:45:33 +0100",
				"request": {
					"protocol": "HTTP/1.1",
					"method": "GET",
					"uri": "http://icanhascheezburger.com/"
				},
				"response": {
					"protocol": "HTTP/1.1",
					"status": 200,
					"body": "O HAI!"
				}
			}
		]
	}
}"""
		when:
		def tape = loader.readTape(new StringReader(json))

		then:
		tape.name == "single_interaction_tape"
		tape.interactions.size() == 1
		tape.interactions[0].recorded == new Date(111, 7, 19, 12, 45, 33)
		tape.interactions[0].request.requestLine.protocolVersion == HTTP_1_1
		tape.interactions[0].request.requestLine.method == "GET"
		tape.interactions[0].request.requestLine.uri == "http://icanhascheezburger.com/"
		tape.interactions[0].response.statusLine.protocolVersion == HTTP_1_1
		tape.interactions[0].response.statusLine.statusCode == HTTP_OK
		tape.interactions[0].response.entity.content.text == "O HAI!"
	}

	def "can load a valid tape with multiple interactions"() {
		given:
		def json = """\
{
	"tape": {
		"name": "single_interaction_tape",
		"interactions": [
			{
				"recorded": "2011-08-19 12:45:33 +0100",
				"request": {
					"protocol": "HTTP/1.1",
					"method": "GET",
					"uri": "http://icanhascheezburger.com/"
				},
				"response": {
					"protocol": "HTTP/1.1",
					"status": 200,
					"body": "O HAI!"
				}
			},
			{
				"recorded": "2011-08-19 21:19:14 +0100",
				"request": {
					"protocol": "HTTP/1.1",
					"method": "GET",
					"uri": "http://en.wikipedia.org/wiki/Hyper_Text_Coffee_Pot_Control_Protocol"
				},
				"response": {
					"protocol": "HTTP/1.1",
					"status": 418,
					"body": "I'm a teapot"
				}
			}
		]
	}
}"""
		when:
		def tape = loader.readTape(new StringReader(json))

		then:
		tape.interactions.size() == 2
		tape.interactions[0].request.requestLine.uri == "http://icanhascheezburger.com/"
		tape.interactions[1].request.requestLine.uri == "http://en.wikipedia.org/wiki/Hyper_Text_Coffee_Pot_Control_Protocol"
		tape.interactions[0].response.statusLine.statusCode == HTTP_OK
		tape.interactions[1].response.statusLine.statusCode == 418
		tape.interactions[0].response.entity.content.text == "O HAI!"
		tape.interactions[1].response.entity.content.text == "I'm a teapot"
	}

	def "barfs on non-json data"() {
		given:
		def json = "THIS IS NOT JSON"

		when:
		loader.readTape(new StringReader(json))

		then:
		def e = thrown(TapeLoadException)
		e.cause instanceof JsonException
	}

	def "barfs on an invalid record date"() {
		given:
		def json = """\
{
	"tape": {
		"name": "invalid_timestamp_tape",
		"interactions": [
			{
				"recorded": "THIS IS NOT A DATE",
				"request": {
					"protocol": "HTTP/1.1",
					"method": "GET",
					"uri": "http://icanhascheezburger.com/"
				},
				"response": {
					"protocol": "HTTP/1.1",
					"status": 200,
					"body": "O HAI!"
				}
			}
		]
	}
}"""
		when:
		loader.readTape(new StringReader(json))

		then:
		def e = thrown(TapeLoadException)
		e.cause instanceof ParseException
	}

}