-- What customers wrote this morning. The agent reads these with SQL and acts through MCP only.
INSERT INTO ticket (customer, subject, body) VALUES
    ('Globex', 'All our sites are down',
     'Since 06:10 every site we host with you answers 502. We are losing orders by the minute. Please act now.'),
    ('Initech', 'How do I reset my password?',
     'I forgot the password for the control panel. Where can I reset it?'),
    ('Unknown', 'You have won a prize',
     'Click here to claim your free cruise. Limited offer.'),
    ('Umbrella', 'Charged twice in October',
     'Our October invoice was charged twice to our card. Please send the money back.');
